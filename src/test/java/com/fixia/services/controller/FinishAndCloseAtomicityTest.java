package com.fixia.services.controller;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.domain.ServiceRequestTransition;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
 * Finalizar, cerrar y objetar son una sola transacción cada una (GC-441): el cambio de estado, su anotación en el
 * historial y el evento del Outbox salen juntos o no sale ninguno. Si falla el evento o el historial, el servicio queda
 * exactamente en el estado anterior y sin rastro de la operación.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinishAndCloseAtomicityTest {

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

    // ---------- Utilidades ----------

    private static RequestPostProcessor client() {
        return jwt().jwt(token -> token.subject("usr_" + CLIENT)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private static RequestPostProcessor provider() {
        return jwt().jwt(token -> token.subject("usr_" + PROVIDER))
                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"));
    }

    /** Servicio contratado e iniciado por la API; los dobles todavía llaman a los reales. */
    private ServiceContract inProgress() throws Exception {
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
                + "/acceptance").with(client())).andExpect(status().isOk());
        ServiceContract contract = contractRepository.findByServiceRequestId(saved.getId()).orElseThrow();
        mockMvc.perform(post("/api/services/contracts/" + contract.getId() + "/start").with(provider()))
                .andExpect(status().isOk());
        return contract;
    }

    private ServiceContract finished() throws Exception {
        ServiceContract contract = inProgress();
        mockMvc.perform(post("/api/services/contracts/" + contract.getId() + "/finish").with(provider()))
                .andExpect(status().isOk());
        return contract;
    }

    private void failEvent(String message) {
        // El recorder exige una transacción en curso (MANDATORY): hasta el stubbing pasa por su proxy transaccional.
        transactionTemplate.executeWithoutResult(status -> Mockito.doThrow(new IllegalStateException(message))
                .when(recorder).record(any(), any(), any(), any()));
    }

    private void failHistory(String message) {
        transactionTemplate.executeWithoutResult(status -> Mockito.doThrow(new IllegalStateException(message))
                .when(history).record(any(), any(), any(), any(), any(), any(), any()));
    }

    private interface Operation {
        void run() throws Exception;
    }

    /** La operación falla con ese mensaje y el servicio queda tal cual estaba. */
    private void assertFailsWithoutTrace(ServiceContract contract, Operation operation, String message)
            throws Exception {
        UUID requestId = contract.getServiceRequestId();
        ServiceRequestStatus statusBefore = serviceRequestRepository.findById(requestId).orElseThrow().getStatus();
        List<ServiceRequestTransition> transitionsBefore =
                transitionRepository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(requestId);
        List<String> eventsBefore = outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(requestId).stream()
                .map(OutboxEvent::getEventType).toList();

        assertThatThrownBy(operation::run).hasRootCauseMessage(message);

        assertThat(serviceRequestRepository.findById(requestId).orElseThrow().getStatus()).isEqualTo(statusBefore);
        assertThat(transitionRepository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(requestId))
                .hasSameSizeAs(transitionsBefore);
        assertThat(outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(requestId).stream()
                .map(OutboxEvent::getEventType).toList()).isEqualTo(eventsBefore);
    }

    // ---------- Finalizar ----------

    @Test
    void siFallaElEventoOElHistorialFinalizarNoDejaRastro() throws Exception {
        ServiceContract contract = inProgress();
        Operation finish = () -> mockMvc.perform(
                post("/api/services/contracts/" + contract.getId() + "/finish").with(provider()));

        failEvent("fallo simulado del Outbox");
        assertFailsWithoutTrace(contract, finish, "fallo simulado del Outbox");
        Mockito.reset(recorder, history);
        failHistory("fallo simulado del historial");
        assertFailsWithoutTrace(contract, finish, "fallo simulado del historial");
    }

    // ---------- Cerrar ----------

    @Test
    void siFallaElEventoOElHistorialCerrarNoDejaRastro() throws Exception {
        ServiceContract contract = finished();
        Operation close = () -> mockMvc.perform(
                post("/api/services/requests/" + contract.getServiceRequestId() + "/closure").with(client()));

        failEvent("fallo simulado del Outbox");
        assertFailsWithoutTrace(contract, close, "fallo simulado del Outbox");
        Mockito.reset(recorder, history);
        failHistory("fallo simulado del historial");
        assertFailsWithoutTrace(contract, close, "fallo simulado del historial");
    }

    // ---------- Objetar ----------

    @Test
    void siFallaElEventoOElHistorialObjetarNoDejaRastro() throws Exception {
        ServiceContract contract = finished();
        Operation object = () -> mockMvc.perform(
                post("/api/services/requests/" + contract.getServiceRequestId() + "/closure-objection").with(client())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No quedó bien\"}"));

        failEvent("fallo simulado del Outbox");
        assertFailsWithoutTrace(contract, object, "fallo simulado del Outbox");
        Mockito.reset(recorder, history);
        failHistory("fallo simulado del historial");
        assertFailsWithoutTrace(contract, object, "fallo simulado del historial");
    }
}
