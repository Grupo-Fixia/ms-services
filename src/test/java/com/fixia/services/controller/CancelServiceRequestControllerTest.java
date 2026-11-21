package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.domain.ServiceRequestTransition;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import com.fixia.services.service.EventContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GC-515: el cliente cancela su solicitud solo antes de contratar (publicada o con prestador elegido). Las postulaciones
 * pendientes quedan rechazadas, la transición queda en el historial y se publica {@code solicitud.cancelada}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CancelServiceRequestControllerTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private QuotationRepository quotationRepository;

    @Autowired
    private ServiceContractRepository contractRepository;

    @Autowired
    private ServiceRequestTransitionRepository transitionRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAllInBatch();
        transitionRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    private ServiceRequest published(UUID clientId) {
        Instant now = Instant.now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(clientId, ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero"), null,
                now.plus(Duration.ofDays(5)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now));
    }

    private ServiceRequestApplication applied(ServiceRequest request, UUID providerId) {
        return applicationRepository.saveAndFlush(ServiceRequestApplication.submit(request, providerId,
                new BigDecimal("150000"), CurrencyCode.COP, Instant.now()));
    }

    /** Solicitud con dos postulantes y {@code PROVIDER} ya elegido. */
    private ServiceRequest selected() {
        ServiceRequest request = published(CLIENT);
        ServiceRequestApplication chosen = applied(request, PROVIDER);
        ServiceRequestApplication other = applied(request, OTHER_PROVIDER);
        Instant now = Instant.now();
        chosen.select(now);
        other.reject(now);
        applicationRepository.saveAllAndFlush(List.of(chosen, other));
        request.selectProvider(PROVIDER, now);
        return serviceRequestRepository.saveAndFlush(request);
    }

    private static RequestPostProcessor client(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private ResultActions cancel(UUID clientId, UUID requestId) throws Exception {
        return mockMvc.perform(post("/api/services/requests/" + requestId + "/cancellation").with(client(clientId)));
    }

    private List<OutboxEvent> cancellations(UUID requestId) {
        return outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(requestId).stream()
                .filter(event -> event.getEventType().equals("solicitud.cancelada")).toList();
    }

    private ServiceRequestStatus statusOf(UUID requestId) {
        return serviceRequestRepository.findById(requestId).orElseThrow().getStatus();
    }

    // ---------- Antes de contratar ----------

    @Test
    void elClienteCancelaUnaSolicitudPublicadaYSusPostulacionesPendientesQuedanRechazadas() throws Exception {
        ServiceRequest request = published(CLIENT);
        ServiceRequestApplication first = applied(request, PROVIDER);
        ServiceRequestApplication second = applied(request, OTHER_PROVIDER);

        cancel(CLIENT, request.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(statusOf(request.getId())).isEqualTo(ServiceRequestStatus.CANCELLED);
        assertThat(applicationRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.REJECTED);
        assertThat(applicationRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.REJECTED);

        List<ServiceRequestTransition> history =
                transitionRepository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(request.getId());
        assertThat(history).last().satisfies(transition -> {
            assertThat(transition.getFromStatus()).isEqualTo(ServiceRequestStatus.PUBLISHED);
            assertThat(transition.getToStatus()).isEqualTo(ServiceRequestStatus.CANCELLED);
            assertThat(transition.getActorId()).isEqualTo(CLIENT);
        });

        List<OutboxEvent> events = cancellations(request.getId());
        assertThat(events).hasSize(1);
        JsonNode envelope = objectMapper.readTree(events.get(0).getPayload());
        assertThat(EventContract.violations(envelope)).isEmpty();
        assertThat(envelope.at("/data/previousStatus").asText()).isEqualTo("PUBLISHED");
        assertThat(envelope.at("/data/selectedProviderId").isNull()).isTrue();
        assertThat(envelope.at("/data/rejectedApplicationIds")).hasSize(2);
    }

    @Test
    void tambienSeCancelaConPrestadorElegidoYLaCotizacionEnviadaYaNoSePuedeAceptar() throws Exception {
        ServiceRequest request = selected();
        Instant now = Instant.now();
        Quotation quotation = quotationRepository.saveAndFlush(Quotation.send(request, PROVIDER,
                new BigDecimal("90000"), CurrencyCode.COP, "Cambio de empaque", "Pago al terminar",
                now.plus(Duration.ofDays(2)), now.plus(Duration.ofDays(1)), 1, now));

        cancel(CLIENT, request.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        JsonNode envelope = objectMapper.readTree(cancellations(request.getId()).get(0).getPayload());
        assertThat(EventContract.violations(envelope)).isEmpty();
        assertThat(envelope.at("/data/previousStatus").asText()).isEqualTo("PROVIDER_SELECTED");
        assertThat(envelope.at("/data/selectedProviderId").asText()).isEqualTo(PROVIDER.toString());
        assertThat(envelope.at("/data/rejectedApplicationIds")).isEmpty();

        mockMvc.perform(post("/api/services/requests/" + request.getId() + "/quotations/" + quotation.getId()
                        + "/acceptance").with(client(CLIENT)))
                .andExpect(status().isConflict());
        assertThat(contractRepository.findByServiceRequestId(request.getId())).isEmpty();
    }

    @Test
    void repetirLaCancelacionDevuelveElEstadoActualSinOtroEvento() throws Exception {
        ServiceRequest request = published(CLIENT);

        cancel(CLIENT, request.getId()).andExpect(status().isOk());
        cancel(CLIENT, request.getId()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(cancellations(request.getId())).hasSize(1);
    }

    // ---------- Después de contratar o ajena ----------

    @Test
    void unaSolicitudContratadaNoSePuedeCancelar() throws Exception {
        ServiceRequest request = selected();
        request.contract(Instant.now());
        serviceRequestRepository.saveAndFlush(request);

        cancel(CLIENT, request.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Solo se puede cancelar una solicitud antes de contratar"));

        assertThat(statusOf(request.getId())).isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(cancellations(request.getId())).isEmpty();
    }

    @Test
    void unaSolicitudAjenaOInexistenteRespondeLoMismo() throws Exception {
        ServiceRequest request = published(CLIENT);

        cancel(OTHER_CLIENT, request.getId()).andExpect(status().isNotFound());
        cancel(CLIENT, UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(statusOf(request.getId())).isEqualTo(ServiceRequestStatus.PUBLISHED);
    }

    @Test
    void unTecnicoNoPuedeCancelarSolicitudes() throws Exception {
        ServiceRequest request = published(CLIENT);

        mockMvc.perform(post("/api/services/requests/" + request.getId() + "/cancellation")
                        .with(jwt().jwt(token -> token.subject("usr_" + PROVIDER))
                                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"))))
                .andExpect(status().isForbidden());
    }
}
