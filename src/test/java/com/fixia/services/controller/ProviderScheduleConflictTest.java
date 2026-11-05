package com.fixia.services.controller;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Un técnico no queda contratado en dos servicios a la misma hora: si ya tiene uno activo el día 1 a las 10:00, no
 * puede enviar ni le pueden aceptar otra cotización con fecha acordada a menos de dos horas de esa
 * ({@code ProviderScheduleGuard}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProviderScheduleConflictTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER = UUID.randomUUID();

    /** El día 1 a las 10:00, pero siempre en el futuro para que la cotización sea válida. */
    private static final Instant BOOKED_AT = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.HOURS);

    @Autowired
    private MockMvc mockMvc;

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

    private ServiceRequest selectedRequest(UUID clientId, UUID providerId) {
        Instant now = Instant.now();
        ServiceRequest request = ServiceRequest.publish(clientId, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero"), null, now.plus(Duration.ofDays(5)),
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), now);
        request.selectProvider(providerId, now);
        return serviceRequestRepository.saveAndFlush(request);
    }

    /** Cotización enviada ahora, vigente un día, con el servicio acordado en {@code agreedAt}. */
    private Quotation sent(ServiceRequest request, UUID providerId, Instant agreedAt) {
        Instant now = Instant.now();
        return quotationRepository.saveAndFlush(Quotation.send(request, providerId, new BigDecimal("90000"),
                CurrencyCode.COP, "Cambio de empaque", "Pago al terminar", agreedAt, now.plus(Duration.ofDays(1)), 1,
                now));
    }

    /** Un servicio ya contratado del prestador en {@code agreedAt}, por el camino real: el cliente acepta. */
    private ServiceRequest contracted(UUID clientId, UUID providerId, Instant agreedAt) throws Exception {
        ServiceRequest request = selectedRequest(clientId, providerId);
        Quotation quotation = sent(request, providerId, agreedAt);
        accept(clientId, request.getId(), quotation.getId()).andExpect(status().isOk());
        return request;
    }

    private static RequestPostProcessor client(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private static RequestPostProcessor professional(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id))
                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"));
    }

    private ResultActions accept(UUID clientId, UUID requestId, UUID quotationId) throws Exception {
        return mockMvc.perform(post("/api/services/requests/" + requestId + "/quotations/" + quotationId
                + "/acceptance").with(client(clientId)));
    }

    private ResultActions send(UUID providerId, UUID requestId, Instant agreedAt) throws Exception {
        String body = """
                {"serviceRequestId":"%s","amount":120000,"currency":"COP","scope":"Cambio de tubería",
                 "conditions":"Incluye materiales","agreedAt":"%s","validUntil":"%s"}
                """.formatted(requestId, agreedAt, Instant.now().plus(Duration.ofDays(1)));
        return mockMvc.perform(post("/api/services/quotations").with(professional(providerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---------- Enviar la cotización ----------

    @Test
    void elTecnicoNoPuedeCotizarALaMismaHoraDeUnServicioQueYaTieneContratado() throws Exception {
        contracted(CLIENT, PROVIDER, BOOKED_AT);
        ServiceRequest other = selectedRequest(OTHER_CLIENT, PROVIDER);

        send(PROVIDER, other.getId(), BOOKED_AT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Horario ocupado"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("2 horas")));

        assertThat(quotationRepository.findByServiceRequestIdOrderByRevisionDesc(other.getId())).isEmpty();
    }

    @Test
    void tampocoPuedeCotizarAMenosDeDosHorasDelServicioContratado() throws Exception {
        contracted(CLIENT, PROVIDER, BOOKED_AT);
        ServiceRequest other = selectedRequest(OTHER_CLIENT, PROVIDER);

        send(PROVIDER, other.getId(), BOOKED_AT.plus(Duration.ofMinutes(90))).andExpect(status().isConflict());
        send(PROVIDER, other.getId(), BOOKED_AT.minus(Duration.ofMinutes(90))).andExpect(status().isConflict());
    }

    @Test
    void puedeCotizarConDosHorasOMasDeDiferencia() throws Exception {
        contracted(CLIENT, PROVIDER, BOOKED_AT);
        ServiceRequest other = selectedRequest(OTHER_CLIENT, PROVIDER);

        send(PROVIDER, other.getId(), BOOKED_AT.plus(Duration.ofHours(2))).andExpect(status().isCreated());
    }

    @Test
    void elServicioContratadoDeOtroTecnicoNoLoBloquea() throws Exception {
        contracted(CLIENT, OTHER_PROVIDER, BOOKED_AT);
        ServiceRequest mine = selectedRequest(OTHER_CLIENT, PROVIDER);

        send(PROVIDER, mine.getId(), BOOKED_AT).andExpect(status().isCreated());
    }

    // ---------- Aceptar la cotización ----------

    @Test
    void elClienteNoPuedeContratarAUnTecnicoQueYaQuedoContratadoALaMismaHora() throws Exception {
        // Las dos cotizaciones se enviaron cuando el técnico todavía estaba libre.
        ServiceRequest first = selectedRequest(CLIENT, PROVIDER);
        Quotation firstQuotation = sent(first, PROVIDER, BOOKED_AT);
        ServiceRequest second = selectedRequest(OTHER_CLIENT, PROVIDER);
        Quotation secondQuotation = sent(second, PROVIDER, BOOKED_AT);

        accept(CLIENT, first.getId(), firstQuotation.getId()).andExpect(status().isOk());
        accept(OTHER_CLIENT, second.getId(), secondQuotation.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Horario ocupado"));

        assertThat(quotationRepository.findById(secondQuotation.getId()).orElseThrow().getStatus())
                .isEqualTo(QuotationStatus.SENT);
        assertThat(serviceRequestRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
        assertThat(contractRepository.findByServiceRequestId(second.getId())).isEmpty();
    }

    @Test
    void repetirLaAceptacionDelMismoServicioNoChocaConsigoMismo() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request, PROVIDER, BOOKED_AT);

        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());
        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());
    }
}
