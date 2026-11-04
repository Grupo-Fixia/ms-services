package com.fixia.services.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Pago virtual de una contratación (GC-490), tal como lo confirmó ms-payments. No guarda saldo ni importes: solo si el
 * total quedó retenido ({@link ContractPaymentStatus#HELD}) o no ({@link ContractPaymentStatus#FAILED}). La ausencia de
 * fila es {@link ContractPaymentStatus#PENDING}.
 *
 * <p>Cada actualización lleva la secuencia del hecho en la historia del pago: un evento con una secuencia menor o igual a
 * la última aplicada llegó tarde o repetido y no cambia nada, así un {@code pago.fallido} atrasado nunca deshace una
 * retención confirmada.
 */
@Entity
@Table(name = "contract_payments")
public class ContractPayment {

    /** Id de la contratación ({@code service_contracts.id}); hay a lo sumo un pago por contratación. */
    @Id
    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    @Column(name = "service_request_id", nullable = false, updatable = false)
    private UUID serviceRequestId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ContractPaymentStatus status;

    @Column(name = "last_event_id", nullable = false)
    private UUID lastEventId;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContractPayment() {
    }

    private ContractPayment(UUID contractId, UUID serviceRequestId, UUID paymentId, ContractPaymentStatus status,
                            UUID eventId, long sequence, Instant now) {
        this.contractId = contractId;
        this.serviceRequestId = serviceRequestId;
        this.paymentId = paymentId;
        this.status = status;
        this.lastEventId = eventId;
        this.lastSequence = sequence;
        this.updatedAt = now;
    }

    /** Primer hecho de pago conocido de la contratación. */
    public static ContractPayment first(UUID contractId, UUID serviceRequestId, UUID paymentId,
                                        ContractPaymentStatus status, UUID eventId, long sequence, Instant now) {
        require(contractId != null && serviceRequestId != null && paymentId != null && eventId != null && now != null,
                "Faltan datos del pago de la contratación");
        require(status == ContractPaymentStatus.HELD || status == ContractPaymentStatus.FAILED,
                "Solo se guarda un pago retenido o fallido");
        require(sequence >= 1, "La secuencia del hecho debe ser positiva");
        return new ContractPayment(contractId, serviceRequestId, paymentId, status, eventId, sequence, now);
    }

    /** ¿Este hecho es más nuevo que el último aplicado? Uno con secuencia menor o igual es tardío o repetido. */
    public boolean isNewer(long sequence) {
        return sequence > lastSequence;
    }

    /**
     * Aplica un hecho más nuevo: de {@code FAILED} a {@code HELD} cuando el cliente recarga y reintenta, o a la inversa.
     *
     * @throws IllegalArgumentException si el hecho no es más nuevo que el último aplicado
     */
    public void apply(UUID paymentId, ContractPaymentStatus status, UUID eventId, long sequence, Instant now) {
        require(isNewer(sequence), "El hecho no es más nuevo que el último aplicado");
        require(status == ContractPaymentStatus.HELD || status == ContractPaymentStatus.FAILED,
                "Solo se guarda un pago retenido o fallido");
        require(paymentId != null && eventId != null && now != null, "Faltan datos del pago de la contratación");
        this.paymentId = paymentId;
        this.status = status;
        this.lastEventId = eventId;
        this.lastSequence = sequence;
        this.updatedAt = now;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public UUID getContractId() {
        return contractId;
    }

    public UUID getServiceRequestId() {
        return serviceRequestId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public ContractPaymentStatus getStatus() {
        return status;
    }

    public UUID getLastEventId() {
        return lastEventId;
    }

    public long getLastSequence() {
        return lastSequence;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ContractPayment other && Objects.equals(contractId, other.contractId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(contractId);
    }
}
