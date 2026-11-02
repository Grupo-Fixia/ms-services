package com.fixia.services.relay;

import com.fixia.services.domain.OutboxStatus;
import com.fixia.services.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Métricas de observabilidad para el proceso Relay del Outbox (GC-434).
 * Registra pendientes, fallidos, edad del evento más antiguo, publicados y reintentos.
 */
@Component
public class OutboxMetrics {

    private final OutboxEventRepository repository;
    private final Counter publishedCounter;
    private final Counter retriesCounter;
    private final Counter failedCounter;

    public OutboxMetrics(OutboxEventRepository repository, MeterRegistry registry) {
        this.repository = repository;

        this.publishedCounter = Counter.builder("outbox.published.total")
                .description("Total de eventos del Outbox publicados exitosamente a Kafka")
                .register(registry);

        this.retriesCounter = Counter.builder("outbox.retries.total")
                .description("Total de reintentos de publicación fallidos en el Outbox")
                .register(registry);

        this.failedCounter = Counter.builder("outbox.failed.total")
                .description("Total de eventos del Outbox que superaron el límite de reintentos y pasaron a FAILED")
                .register(registry);

        Gauge.builder("outbox.pending.count", this, OutboxMetrics::getPendingCount)
                .description("Número de eventos actualmente en estado PENDING en el Outbox")
                .register(registry);

        Gauge.builder("outbox.failed.count", this, OutboxMetrics::getFailedCount)
                .description("Número de eventos actualmente en estado FAILED en el Outbox")
                .register(registry);

        Gauge.builder("outbox.oldest.age.seconds", this, OutboxMetrics::getOldestPendingAgeSeconds)
                .description("Edad en segundos del evento PENDING más antiguo en el Outbox")
                .register(registry);
    }

    public void recordPublished() {
        publishedCounter.increment();
    }

    public void recordRetry() {
        retriesCounter.increment();
    }

    public void recordFailed() {
        failedCounter.increment();
    }

    double getPendingCount() {
        return repository.countByStatus(OutboxStatus.PENDING);
    }

    double getFailedCount() {
        return repository.countByStatus(OutboxStatus.FAILED);
    }

    double getOldestPendingAgeSeconds() {
        return repository.findFirstByStatusOrderByOccurredAtAsc(OutboxStatus.PENDING)
                .map(event -> (double) Math.max(0, Duration.between(event.getOccurredAt(), Instant.now()).toSeconds()))
                .orElse(0.0);
    }
}

