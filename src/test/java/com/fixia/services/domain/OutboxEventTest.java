package com.fixia.services.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Evento del Outbox (GC-433): el hecho no cambia y el estado de envío sigue sus reglas. */
class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-10-26T15:00:00Z");
    private static final UUID REQUEST = UUID.randomUUID();

    private static OutboxEvent pending() {
        return OutboxEvent.pending(UUID.randomUUID(), "solicitud.publicada", (short) 1, "ServiceRequest", REQUEST, 1,
                "{\"secreto\":\"no se imprime\"}", NOW);
    }

    @Test
    void nacePendienteYListoParaPublicarDesdeYa() {
        OutboxEvent event = pending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(event.getTopic()).isEqualTo("fixia.services.service-requests");
        assertThat(event.getMessageKey()).isEqualTo(REQUEST.toString());
        assertThat(event.isNew()).isTrue();
        assertThat(event.getId()).isEqualTo(event.getEventId());
    }

    @Test
    void exigeLosDatosDelHecho() {
        assertThatThrownBy(() -> OutboxEvent.pending(null, "t", (short) 1, "A", REQUEST, 1, "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.pending(UUID.randomUUID(), " ", (short) 1, "A", REQUEST, 1, "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.pending(UUID.randomUUID(), "t", (short) 0, "A", REQUEST, 1, "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.pending(UUID.randomUUID(), "t", (short) 1, "", REQUEST, 1, "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.pending(UUID.randomUUID(), "t", (short) 1, "A", null, 1, "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.pending(UUID.randomUUID(), "t", (short) 1, "A", REQUEST, 0, "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.pending(UUID.randomUUID(), "t", (short) 1, "A", REQUEST, 1, "", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.pending(UUID.randomUUID(), "t", (short) 1, "A", REQUEST, 1,
                "x".repeat(OutboxEvent.MAX_PAYLOAD_LENGTH + 1), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.pending(UUID.randomUUID(), "t", (short) 1, "A", REQUEST, 1, "{}", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicarloEsUnaSolaVezAunqueSeRepita() {
        OutboxEvent event = pending();
        event.registerFailedAttempt("timeout", NOW);

        event.markPublished(NOW.plusSeconds(5));
        event.markPublished(NOW.plusSeconds(60));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(event.getLastError()).isNull();
        assertThatThrownBy(() -> event.registerFailedAttempt("tarde", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cadaFalloEsperaElDobleHastaCincoMinutosYAlDecimoQuedaFallido() {
        OutboxEvent event = pending();
        long[] expectedSeconds = {2, 4, 8, 16, 32, 64, 128, 256, 300};

        for (long seconds : expectedSeconds) {
            event.registerFailedAttempt("broker no disponible", NOW);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(Duration.between(NOW, event.getNextAttemptAt())).hasSeconds(seconds);
        }
        event.registerFailedAttempt("broker no disponible", NOW);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(OutboxEvent.MAX_ATTEMPTS);
        assertThat(OutboxEvent.backoff(30)).isEqualTo(OutboxEvent.MAX_BACKOFF);
    }

    @Test
    void unFallidoSeReactivaYSoloUnFallido() {
        OutboxEvent event = pending();
        assertThatThrownBy(() -> event.requeue(NOW)).isInstanceOf(IllegalStateException.class);
        for (int i = 0; i < OutboxEvent.MAX_ATTEMPTS; i++) {
            event.registerFailedAttempt("error", NOW);
        }
        assertThatThrownBy(() -> event.markPublished(NOW)).isInstanceOf(IllegalStateException.class);

        event.requeue(NOW.plusSeconds(3600));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void elErrorSeRecortaYElContenidoNoSeImprime() {
        OutboxEvent event = pending();
        event.registerFailedAttempt("e".repeat(800), NOW);
        event.registerFailedAttempt(null, NOW);
        OutboxEvent other = pending();
        other.registerFailedAttempt("e".repeat(800), NOW);

        assertThat(event.getLastError()).isNull();
        assertThat(other.getLastError()).hasSize(OutboxEvent.MAX_ERROR_LENGTH);
        assertThat(event.toString()).contains("solicitud.publicada").doesNotContain("no se imprime");
    }

    @Test
    void dosEventosSonIgualesSoloSiTienenElMismoId() {
        OutboxEvent event = pending();

        assertThat(event).isEqualTo(event).isNotEqualTo(pending()).isNotEqualTo("otro");
        assertThat(event.hashCode()).isEqualTo(pending().hashCode());
    }
}
