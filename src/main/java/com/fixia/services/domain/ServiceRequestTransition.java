package com.fixia.services.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Un cambio de estado de la solicitud, tal como lo ven el cliente y el prestador (GC-437): de qué estado a cuál, quién lo
 * hizo y cuándo. Es un hecho: una vez creado no cambia ({@code updatable = false}) y solo se anexan filas. No reemplaza
 * al Outbox, que es técnico y sirve para publicar eventos.
 */
@Entity
@Table(name = "service_request_transitions")
public class ServiceRequestTransition {

    public static final int MAX_REASON_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "service_request_id", nullable = false, updatable = false)
    private UUID serviceRequestId;

    /** Nulo en la publicación: no hay estado de origen. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30, updatable = false)
    private ServiceRequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30, updatable = false)
    private ServiceRequestStatus toStatus;

    /** {@code users.id} de quien hizo el cambio; nulo solo si lo hizo el sistema. */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 20, updatable = false)
    private ActorRole actorRole;

    @Column(name = "reason", length = MAX_REASON_LENGTH, updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected ServiceRequestTransition() {
    }

    private ServiceRequestTransition(UUID serviceRequestId, ServiceRequestStatus fromStatus,
                                     ServiceRequestStatus toStatus, UUID actorId, ActorRole actorRole, String reason,
                                     Instant occurredAt) {
        this.serviceRequestId = serviceRequestId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.reason = reason == null || reason.isBlank() ? null : reason.strip();
        this.occurredAt = occurredAt;
    }

    /**
     * @param fromStatus estado anterior; nulo solo en la publicación
     * @throws IllegalArgumentException si falta un dato obligatorio, si no hay actor y no lo hizo el sistema, o si el
     *                                  motivo supera {@value #MAX_REASON_LENGTH} caracteres
     */
    public static ServiceRequestTransition of(UUID serviceRequestId, ServiceRequestStatus fromStatus,
                                              ServiceRequestStatus toStatus, UUID actorId, ActorRole actorRole,
                                              String reason, Instant occurredAt) {
        require(serviceRequestId != null, "La solicitud es obligatoria");
        require(toStatus != null, "El estado nuevo es obligatorio");
        require(actorRole != null, "El rol del actor es obligatorio");
        require(actorId != null || actorRole == ActorRole.SYSTEM, "El actor es obligatorio");
        require(occurredAt != null, "El momento es obligatorio");
        require(reason == null || reason.strip().length() <= MAX_REASON_LENGTH,
                "El motivo no puede superar " + MAX_REASON_LENGTH + " caracteres");
        return new ServiceRequestTransition(serviceRequestId, fromStatus, toStatus, actorId, actorRole, reason,
                occurredAt);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getServiceRequestId() {
        return serviceRequestId;
    }

    public ServiceRequestStatus getFromStatus() {
        return fromStatus;
    }

    public ServiceRequestStatus getToStatus() {
        return toStatus;
    }

    public UUID getActorId() {
        return actorId;
    }

    public ActorRole getActorRole() {
        return actorRole;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
