package com.fixia.services.relay;

import com.fixia.services.domain.OutboxEvent;

import java.util.concurrent.CompletableFuture;

/**
 * Publicador de eventos del Outbox a la plataforma de mensajería (Kafka).
 */
public interface OutboxPublisher {

    /**
     * Publica el evento en el tópico correspondiente con su clave y cabeceras de contrato (GC-436).
     *
     * @param event Evento del Outbox a publicar.
     * @return CompletableFuture que se completa cuando el broker acusa el recibo del mensaje.
     */
    CompletableFuture<Void> publish(OutboxEvent event);
}

