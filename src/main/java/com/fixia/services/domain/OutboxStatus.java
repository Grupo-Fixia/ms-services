package com.fixia.services.domain;

/**
 * Estado de envío de un evento del Outbox (GC-433). Lo actualiza solo el relay (GC-434); el hecho no cambia.
 */
public enum OutboxStatus {

    /** Esperando publicación, o reintento programado tras un fallo. */
    PENDING,

    /** Kafka confirmó la publicación. */
    PUBLISHED,

    /** Agotó sus reintentos: queda visible y se reactiva volviéndolo a {@link #PENDING}. */
    FAILED
}
