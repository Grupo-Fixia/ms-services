package com.fixia.services.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Escritura en el Outbox (GC-433): solo dentro de una transacción, desaparece con su rollback, numera sin huecos ni
 * repetidos aunque haya operaciones simultáneas, y la base protege la secuencia.
 */
@SpringBootTest
@ActiveProfiles("test")
class BusinessEventRecorderTest {

    @Autowired
    private BusinessEventRecorder recorder;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TechnicianEligibilityClient eligibilityClient;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
        when(eligibilityClient.check(any(), any()))
                .thenReturn(new TechnicianEligibilityClient.Result(UUID.randomUUID(), true, List.of()));
    }

    private ServiceRequest publishedRequest() {
        Instant now = Instant.now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(UUID.randomUUID(),
                ServiceCategory.PLUMBING, "Fuga de agua", new ApproximateLocation("Bogotá", "Chapinero"), null,
                now.plus(Duration.ofDays(2)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now));
    }

    private OutboxEvent record(ServiceRequest request) {
        return recorder.record(BusinessEvents.requestPublished(request), request.getId(),
                BusinessEvents.Actor.client(request.getClientId()), request.getPublishedAt());
    }

    @Test
    void fueraDeUnaTransaccionNoEscribeNadaYFalla() {
        ServiceRequest request = publishedRequest();

        assertThatThrownBy(() -> record(request)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void siLaTransaccionSeRevierteElEventoNoExiste() {
        ServiceRequest request = publishedRequest();

        transactionTemplate.executeWithoutResult(status -> {
            record(request);
            status.setRollbackOnly();
        });

        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void elEventoConfirmadoQuedaPendienteConElSobreDelContrato() throws Exception {
        ServiceRequest request = publishedRequest();

        OutboxEvent saved = transactionTemplate.execute(status -> record(request));

        OutboxEvent found = outboxRepository.findById(saved.getEventId()).orElseThrow();
        assertThat(found.getAggregateType()).isEqualTo("ServiceRequest");
        assertThat(found.getAggregateId()).isEqualTo(request.getId());
        assertThat(found.getAggregateSequence()).isEqualTo(1);
        assertThat(found.getSchemaVersion()).isEqualTo((short) 1);
        assertThat(EventContract.violations(objectMapper.readTree(found.getPayload()))).isEmpty();
    }

    @Test
    void laSolicitudPublicadaLlevaElPresupuestoOrientativoComoTextoDecimal() throws Exception {
        ServiceRequest request = publishedRequest();

        OutboxEvent saved = transactionTemplate.execute(status -> record(request));

        var budget = objectMapper.readTree(outboxRepository.findById(saved.getEventId()).orElseThrow().getPayload())
                .at("/data/budget");
        assertThat(budget.get("amount").asText()).isEqualTo("200000.00");
        assertThat(budget.get("currency").asText()).isEqualTo("COP");
    }

    @Test
    void laBaseRechazaDosEventosConLaMismaSecuencia() {
        ServiceRequest request = publishedRequest();
        transactionTemplate.executeWithoutResult(status -> record(request));

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO outbox_events (event_id, event_type, "
                + "schema_version, aggregate_type, aggregate_id, aggregate_sequence, topic, message_key, payload, "
                + "occurred_at, status, attempts, next_attempt_at) VALUES (?, 'solicitud.publicada', 1, "
                + "'ServiceRequest', ?, 1, 't', 'k', '{}', CURRENT_TIMESTAMP, 'PENDING', 0, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), request.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void postulacionesSimultaneasALaMismaSolicitudQuedanNumeradasSinHuecosNiRepetidos() throws Exception {
        ServiceRequest request = publishedRequest();
        int technicians = 8;
        ExecutorService pool = Executors.newFixedThreadPool(technicians);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (int i = 0; i < technicians; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return applicationService.apply(UUID.randomUUID(), request.getId(), new BigDecimal("100000"),
                            com.fixia.services.domain.CurrencyCode.COP);
                }));
            }
            start.countDown();
            for (Future<?> future : pending) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(request.getId()))
                .extracting(OutboxEvent::getAggregateSequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }
}
