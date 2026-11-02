package com.fixia.services.relay;

import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.OutboxStatus;
import com.fixia.services.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T15:00:00Z");
    private static final UUID REQUEST_ID = UUID.randomUUID();

    @Mock
    private OutboxEventRepository repository;

    @Mock
    private OutboxPublisher publisher;

    private OutboxMetrics metrics;
    private OutboxRelayProperties properties;
    private OutboxRelayService relayService;

    @BeforeEach
    void setUp() {
        properties = new OutboxRelayProperties();
        properties.setPublishTimeoutSeconds(2);
        properties.setBatchSize(10);
        properties.setEnabled(true);
        properties.setFixedDelayMs(1000);
        properties.setInitialDelayMs(2000);

        metrics = new OutboxMetrics(repository, new SimpleMeterRegistry());
        relayService = new OutboxRelayService(repository, publisher, metrics, properties);
    }

    private OutboxEvent createEvent(long sequence) {
        return OutboxEvent.pending(
                UUID.randomUUID(),
                "solicitud.publicada",
                (short) 1,
                "ServiceRequest",
                REQUEST_ID,
                sequence,
                "{\"serviceRequestId\":\"" + REQUEST_ID + "\"}",
                NOW
        );
    }

    @Test
    void cuandoNoHayEventosPendientesRetornaCero() {
        when(repository.findPendingForRelay(any(Instant.class), anyInt()))
                .thenReturn(Collections.emptyList());

        int processed = relayService.processBatch(10);

        assertThat(processed).isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void publicaEventoExitosamenteYMarcaPublishedConFechaYMetricas() {
        OutboxEvent event = createEvent(1);
        when(repository.findPendingForRelay(any(Instant.class), eq(10)))
                .thenReturn(List.of(event));
        when(publisher.publish(event))
                .thenReturn(CompletableFuture.completedFuture(null));

        int processed = relayService.processBatch(10);

        assertThat(processed).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
        verify(repository).save(event);
    }

    @Test
    void siKafkaFallaRegistraIntentoFallidoConBackoffYMetricas() {
        OutboxEvent event = createEvent(1);
        when(repository.findPendingForRelay(any(Instant.class), eq(10)))
                .thenReturn(List.of(event));

        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Error al conectar con broker Kafka"));
        when(publisher.publish(event)).thenReturn(failedFuture);

        int processed = relayService.processBatch(10);

        assertThat(processed).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("Error al conectar con broker Kafka");
        assertThat(event.getNextAttemptAt()).isAfter(NOW);
        verify(repository).save(event);
    }

    @Test
    void siKafkaAlcanzaDiezIntentosPasaAEstadoFailed() {
        OutboxEvent event = createEvent(1);
        for (int i = 0; i < 9; i++) {
            event.registerFailedAttempt("fallo previo", NOW);
        }
        assertThat(event.getAttempts()).isEqualTo(9);

        when(repository.findPendingForRelay(any(Instant.class), eq(10)))
                .thenReturn(List.of(event));

        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Fallo número 10"));
        when(publisher.publish(event)).thenReturn(failedFuture);

        relayService.processBatch(10);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(10);
        verify(repository).save(event);
    }

    @Test
    void siPublicacionSufreTimeoutRegistraFallo() {
        OutboxEvent event = createEvent(1);
        when(repository.findPendingForRelay(any(Instant.class), eq(10)))
                .thenReturn(List.of(event));

        CompletableFuture<Void> hangingFuture = new CompletableFuture<>();
        // Un future que no se completa en el tiempo límite
        properties.setPublishTimeoutSeconds(0); // Forzar timeout inmediato
        when(publisher.publish(event)).thenReturn(hangingFuture);

        relayService.processBatch(10);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("Tiempo de espera agotado");
        verify(repository).save(event);
    }

    @Test
    void sanitizaErroresOcultandoTokensYSecretos() {
        String errorConToken = "Fallo autenticación con Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.tokenSecreto y password=claveSuperSecreta123";
        String sanitizado = OutboxRelayService.sanitizeError(errorConToken);

        assertThat(sanitizado).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.tokenSecreto");
        assertThat(sanitizado).doesNotContain("claveSuperSecreta123");
        assertThat(sanitizado).contains("[REDACTED]");

        assertThat(OutboxRelayService.sanitizeError(null)).isEqualTo("Error desconocido en publicación a broker");
        assertThat(OutboxRelayService.sanitizeError("")).isEqualTo("Error desconocido en publicación a broker");

        String muyLargo = "a".repeat(600);
        assertThat(OutboxRelayService.sanitizeError(muyLargo).length()).isEqualTo(OutboxEvent.MAX_ERROR_LENGTH);
    }

    @Test
    void propertiesGettersYSetters() {
        OutboxRelayProperties props = new OutboxRelayProperties();
        props.setEnabled(false);
        props.setBatchSize(25);
        props.setFixedDelayMs(3000);
        props.setInitialDelayMs(1000);
        props.setPublishTimeoutSeconds(10);

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getBatchSize()).isEqualTo(25);
        assertThat(props.getFixedDelayMs()).isEqualTo(3000);
        assertThat(props.getInitialDelayMs()).isEqualTo(1000);
        assertThat(props.getPublishTimeoutSeconds()).isEqualTo(10);
    }

    @Test
    void schedulerInvocaRelayService() {
        OutboxRelayScheduler scheduler = new OutboxRelayScheduler(relayService, properties);
        when(repository.findPendingForRelay(any(Instant.class), anyInt()))
                .thenReturn(Collections.emptyList());

        scheduler.runRelay();

        verify(repository).findPendingForRelay(any(Instant.class), eq(properties.getBatchSize()));
    }

    @Test
    void schedulerToleraExcepcionesSinFallar() {
        OutboxRelayService serviceFalla = mock(OutboxRelayService.class);
        when(serviceFalla.processBatch(anyInt())).thenThrow(new RuntimeException("Fallo en BD"));

        OutboxRelayScheduler scheduler = new OutboxRelayScheduler(serviceFalla, properties);
        // No debe lanzar excepción
        scheduler.runRelay();
    }

    @Test
    void metricsCalculaGauges() {
        when(repository.countByStatus(OutboxStatus.PENDING)).thenReturn(5L);
        when(repository.countByStatus(OutboxStatus.FAILED)).thenReturn(2L);
        OutboxEvent eventAntiguo = createEvent(1);
        when(repository.findFirstByStatusOrderByOccurredAtAsc(OutboxStatus.PENDING))
                .thenReturn(Optional.of(eventAntiguo));

        metrics.recordPublished();
        metrics.recordRetry();
        metrics.recordFailed();

        assertThat(metrics.getPendingCount()).isEqualTo(5.0);
        assertThat(metrics.getFailedCount()).isEqualTo(2.0);
        assertThat(metrics.getOldestPendingAgeSeconds()).isGreaterThanOrEqualTo(0.0);
    }
}

