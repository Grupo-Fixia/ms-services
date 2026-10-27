package com.fixia.services.controller;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import com.fixia.services.service.BusinessEventRecorder;
import com.fixia.services.service.ServiceRequestHistory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Iniciar el servicio es una sola transacción (GC-437): el cambio de estado, su anotación en el historial y el evento del
 * Outbox salen juntos o no sale ninguno. Si el evento o el historial fallan, el servicio sigue contratado y no queda
 * rastro del inicio.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StartServiceAtomicityTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();

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

    @Autowired
    private TransactionTemplate transactionTemplate;

    @SpyBean
    private BusinessEventRecorder recorder;

    @SpyBean
    private ServiceRequestHistory history;

    @AfterEach
    void restoreSpies() {
        Mockito.reset(recorder, history);
    }

    @BeforeEach
    void cleanDatabase() {
        Mockito.reset(recorder, history);
        outboxRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    /** Servicio contratado por la API; devuelve la contratación. Los dobles todavía llaman a los reales. */
    private ServiceContract contracted() throws Exception {
        Instant now = Instant.now();
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga de agua",
                new ApproximateLocation("Bogotá", "Chapinero"), new ExactLocation("Calle 10 # 5-20"),
                now.plus(Duration.ofDays(5)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now);
        request.selectProvider(PROVIDER, now);
        ServiceRequest saved = serviceRequestRepository.saveAndFlush(request);
        Quotation quotation = quotationRepository.saveAndFlush(Quotation.send(saved, PROVIDER,
                new BigDecimal("90000"), CurrencyCode.COP, "Alcance", "Condiciones", now.plus(Duration.ofDays(3)),
                now.plus(Duration.ofDays(1)), now));
        mockMvc.perform(post("/api/services/requests/" + saved.getId() + "/quotations/" + quotation.getId()
                        + "/acceptance")
                .with(jwt().jwt(token -> token.subject("usr_" + CLIENT))
                        .authorities(new SimpleGrantedAuthority("ROLE_CLIENT")))).andExpect(status().isOk());
        return contractRepository.findByServiceRequestId(saved.getId()).orElseThrow();
    }

    private void tryToStart(ServiceContract contract, String expectedFailure) {
        assertThatThrownBy(() -> mockMvc.perform(post("/api/services/contracts/" + contract.getId() + "/start")
                .with(jwt().jwt(token -> token.subject("usr_" + PROVIDER))
                        .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL")))))
                .hasRootCauseMessage(expectedFailure);
    }

    private void assertNothingChanged(ServiceContract contract) {
        UUID requestId = contract.getServiceRequestId();
        assertThat(serviceRequestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.CONTRACTED);
        // Solo queda la transición de la contratación, anotada al aceptar la cotización.
        assertThat(transitionRepository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(requestId)).hasSize(1);
        assertThat(outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(requestId))
                .extracting(event -> event.getEventType()).containsExactly("servicio.contratado");
    }

    @Test
    void siFallaElEventoNoQuedaNiElEstadoNiElHistorial() throws Exception {
        ServiceContract contract = contracted();
        // El recorder exige una transacción en curso (MANDATORY): hasta el stubbing pasa por su proxy transaccional.
        transactionTemplate.executeWithoutResult(status -> Mockito
                .doThrow(new IllegalStateException("fallo simulado del Outbox"))
                .when(recorder).record(any(), any(), any(), any()));

        tryToStart(contract, "fallo simulado del Outbox");

        assertNothingChanged(contract);
    }

    @Test
    void siFallaElHistorialNoQuedaNiElEstadoNiElEvento() throws Exception {
        ServiceContract contract = contracted();
        transactionTemplate.executeWithoutResult(status -> Mockito
                .doThrow(new IllegalStateException("fallo simulado del historial"))
                .when(history).record(any(), any(), any(), any(), any(), any(), any()));

        tryToStart(contract, "fallo simulado del historial");

        assertNothingChanged(contract);
    }
}
