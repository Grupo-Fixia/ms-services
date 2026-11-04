package com.fixia.services.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento de negocio en el Outbox transaccional (GC-433, contrato en {@code docs/events/README.md}). Se escribe en la
 * misma transacción que el cambio que lo produjo, así que existe si y solo si ese cambio quedó confirmado.
 *
 * <p>Tiene dos partes: el <b>hecho</b> (identidad, tipo, solicitud, secuencia y el sobre JSON completo), que no cambia
 * nunca, y el <b>envío</b> (estado, intentos, próximo intento, último error y fecha de publicación), que solo actualiza
 * el relay (GC-434) con {@link #markPublished}, {@link #registerFailedAttempt} y {@link #requeue}.
 *
 * <p>El id lo asigna la aplicación, porque también va dentro del sobre: por eso implementa {@link Persistable}, para
 * que guardarlo sea un INSERT y no una consulta previa.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent implements Persistable<UUID> {

    /** Tópico único de los hechos de las solicitudes de servicio; la clave es el id de la solicitud. */
    public static final String TOPIC = "fixia.services.service-requests";
    public static final int MAX_PAYLOAD_LENGTH = 16000;
    public static final int MAX_ERROR_LENGTH = 500;
    /** Tras este número de fallos el evento pasa a {@link OutboxStatus#FAILED}. */
    public static final int MAX_ATTEMPTS = 10;
    /** Espera máxima entre reintentos. */
    public static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 60, updatable = false)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private Short schemaVersion;

    @Column(name = "aggregate_type", nullable = false, length = 40, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_sequence", nullable = false, updatable = false)
    private Long aggregateSequence;

    @Column(name = "topic", nullable = false, length = 120, updatable = false)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 60, updatable = false)
    private String messageKey;

    /** El sobre completo en JSON, tal como se publicará. */
    @Column(name = "payload", nullable = false, length = MAX_PAYLOAD_LENGTH, updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Transient
    private boolean isNew;

    protected OutboxEvent() {
    }

    private OutboxEvent(UUID eventId, String eventType, short schemaVersion, String aggregateType, UUID aggregateId,
                        long aggregateSequence, String payload, Instant occurredAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.aggregateSequence = aggregateSequence;
        this.topic = TOPIC;
        this.messageKey = aggregateId.toString();
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = occurredAt;
        this.isNew = true;
    }

    /**
     * Un hecho nuevo, pendiente de publicar desde ya.
     *
     * @throws IllegalArgumentException si falta un dato, la secuencia o la versión no son positivas o el sobre no
     *                                  cabe en la columna
     */
    public static OutboxEvent pending(UUID eventId, String eventType, short schemaVersion, String aggregateType,
                                      UUID aggregateId, long aggregateSequence, String payload, Instant occurredAt) {
        require(eventId != null, "El id del evento es obligatorio");
        require(eventType != null && !eventType.isBlank(), "El tipo del evento es obligatorio");
        require(schemaVersion >= 1, "La versión del esquema debe ser positiva");
        require(aggregateType != null && !aggregateType.isBlank(), "El tipo del agregado es obligatorio");
        require(aggregateId != null, "El agregado es obligatorio");
        require(aggregateSequence >= 1, "La secuencia debe ser positiva");
        require(payload != null && !payload.isBlank(), "El contenido del evento es obligatorio");
        require(payload.length() <= MAX_PAYLOAD_LENGTH, "El evento supera " + MAX_PAYLOAD_LENGTH + " caracteres");
        require(occurredAt != null, "El instante del hecho es obligatorio");
        return new OutboxEvent(eventId, eventType, schemaVersion, aggregateType, aggregateId, aggregateSequence,
                payload, occurredAt);
    }

    /**
     * Kafka confirmó la publicación. Repetirlo sobre un evento ya publicado no cambia nada (reentrega).
     *
     * @throws IllegalStateException si el evento está {@code FAILED}: primero hay que reactivarlo
     */
    public void markPublished(Instant now) {
        if (status == OutboxStatus.PUBLISHED) {
            return;
        }
        if (status == OutboxStatus.FAILED) {
            throw new IllegalStateException("Un evento fallido se reactiva antes de publicarlo");
        }
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    /**
     * Un intento de publicación falló: suma el intento y programa el siguiente con espera exponencial (2, 4, 8…
     * segundos, máximo {@link #MAX_BACKOFF}). Al llegar a {@link #MAX_ATTEMPTS} queda {@code FAILED}. El error se
     * recorta a {@link #MAX_ERROR_LENGTH} caracteres y nunca debe incluir el contenido del evento.
     *
     * @throws IllegalStateException si el evento ya está publicado
     */
    public void registerFailedAttempt(String error, Instant now) {
        if (status == OutboxStatus.PUBLISHED) {
            throw new IllegalStateException("El evento ya está publicado");
        }
        this.attempts++;
        this.lastError = truncate(error);
        if (attempts >= MAX_ATTEMPTS) {
            this.status = OutboxStatus.FAILED;
        } else {
            this.nextAttemptAt = now.plus(backoff(attempts));
        }
    }

    /**
     * Reactiva un evento fallido para que el relay lo vuelva a intentar desde ya, con los intentos en cero.
     *
     * @throws IllegalStateException si el evento no está {@code FAILED}
     */
    public void requeue(Instant now) {
        if (status != OutboxStatus.FAILED) {
            throw new IllegalStateException("Solo se reactiva un evento fallido");
        }
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
    }

    /**
     * Espera antes del intento siguiente: 2^intentos segundos (2, 4… 256) y, desde el noveno, el tope
     * {@link #MAX_BACKOFF}.
     */
    static Duration backoff(int attempts) {
        return attempts >= 9 ? MAX_BACKOFF : Duration.ofSeconds(1L << attempts);
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public short getSchemaVersion() {
        return schemaVersion;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public long getAggregateSequence() {
        return aggregateSequence;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    /** El contenido puede llevar datos restringidos: no se imprime. */
    @Override
    public String toString() {
        return "OutboxEvent[eventId=" + eventId + ", type=" + eventType + ", aggregateId=" + aggregateId
                + ", sequence=" + aggregateSequence + ", status=" + status + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof OutboxEvent that && eventId != null && Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return OutboxEvent.class.hashCode();
    }
}
