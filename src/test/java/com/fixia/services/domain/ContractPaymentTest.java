package com.fixia.services.domain;

import com.fixia.services.exception.PaymentNotRetainedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pago virtual de una contratación (GC-490): solo guarda retenido o fallido y solo avanza con hechos más nuevos. */
class ContractPaymentTest {

    private static final UUID CONTRACT = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID PAYMENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-03T17:45:00Z");

    private static ContractPayment held(long sequence) {
        return ContractPayment.first(CONTRACT, REQUEST, PAYMENT, ContractPaymentStatus.HELD, UUID.randomUUID(),
                sequence, NOW);
    }

    @Test
    void elPrimerHechoGuardaElEstadoLaSecuenciaYElEvento() {
        UUID eventId = UUID.randomUUID();

        ContractPayment payment = ContractPayment.first(CONTRACT, REQUEST, PAYMENT, ContractPaymentStatus.FAILED,
                eventId, 2, NOW);

        assertThat(payment.getContractId()).isEqualTo(CONTRACT);
        assertThat(payment.getServiceRequestId()).isEqualTo(REQUEST);
        assertThat(payment.getPaymentId()).isEqualTo(PAYMENT);
        assertThat(payment.getStatus()).isEqualTo(ContractPaymentStatus.FAILED);
        assertThat(payment.getLastEventId()).isEqualTo(eventId);
        assertThat(payment.getLastSequence()).isEqualTo(2);
        assertThat(payment.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void pendienteNoSeGuarda() {
        assertThatThrownBy(() -> ContractPayment.first(CONTRACT, REQUEST, PAYMENT, ContractPaymentStatus.PENDING,
                UUID.randomUUID(), 1, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exigeTodosLosDatosYUnaSecuenciaPositiva() {
        UUID event = UUID.randomUUID();
        assertThatThrownBy(() -> ContractPayment.first(null, REQUEST, PAYMENT, ContractPaymentStatus.HELD, event, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractPayment.first(CONTRACT, REQUEST, PAYMENT, ContractPaymentStatus.HELD, null, 1,
                NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractPayment.first(CONTRACT, REQUEST, PAYMENT, ContractPaymentStatus.HELD, event, 0,
                NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void soloUnHechoConMayorSecuenciaEsMasNuevo() {
        ContractPayment payment = held(3);

        assertThat(payment.isNewer(4)).isTrue();
        assertThat(payment.isNewer(3)).isFalse();
        assertThat(payment.isNewer(2)).isFalse();
    }

    @Test
    void aplicarUnHechoMasNuevoCambiaElEstado() {
        ContractPayment payment = ContractPayment.first(CONTRACT, REQUEST, PAYMENT, ContractPaymentStatus.FAILED,
                UUID.randomUUID(), 2, NOW);
        UUID eventId = UUID.randomUUID();
        Instant later = NOW.plusSeconds(60);

        payment.apply(PAYMENT, ContractPaymentStatus.HELD, eventId, 3, later);

        assertThat(payment.getStatus()).isEqualTo(ContractPaymentStatus.HELD);
        assertThat(payment.getLastEventId()).isEqualTo(eventId);
        assertThat(payment.getLastSequence()).isEqualTo(3);
        assertThat(payment.getUpdatedAt()).isEqualTo(later);
    }

    @Test
    void noAplicaUnHechoViejoNiRepetidoNiPendiente() {
        ContractPayment payment = held(3);
        UUID event = UUID.randomUUID();

        assertThatThrownBy(() -> payment.apply(PAYMENT, ContractPaymentStatus.FAILED, event, 3, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payment.apply(PAYMENT, ContractPaymentStatus.FAILED, event, 2, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payment.apply(PAYMENT, ContractPaymentStatus.PENDING, event, 4, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payment.apply(null, ContractPaymentStatus.FAILED, event, 4, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(payment.getStatus()).isEqualTo(ContractPaymentStatus.HELD);
    }

    @Test
    void laIgualdadEsPorContratacion() {
        assertThat(held(1)).isEqualTo(held(5)).hasSameHashCodeAs(held(5));
        assertThat(held(1)).isNotEqualTo(ContractPayment.first(UUID.randomUUID(), REQUEST, PAYMENT,
                ContractPaymentStatus.HELD, UUID.randomUUID(), 1, NOW));
    }

    @Test
    void elMensajeDelConflictoNoRevelaCifrasYDistingueElMotivo() {
        PaymentNotRetainedException pending = new PaymentNotRetainedException(ContractPaymentStatus.PENDING);
        PaymentNotRetainedException failed = new PaymentNotRetainedException(ContractPaymentStatus.FAILED);

        assertThat(pending.getPaymentStatus()).isEqualTo(ContractPaymentStatus.PENDING);
        assertThat(pending.getMessage()).contains("aún no está retenido").doesNotContainPattern("\\d{3,}");
        assertThat(failed.getPaymentStatus()).isEqualTo(ContractPaymentStatus.FAILED);
        assertThat(failed.getMessage()).contains("saldo insuficiente").doesNotContainPattern("\\d{3,}");
    }
}
