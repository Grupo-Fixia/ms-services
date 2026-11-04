package com.fixia.services.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Escribe los hechos de negocio en el Outbox (GC-433), dentro de la transacción del cambio que los produjo: si esa
 * transacción se revierte, el evento tampoco existe. Exige una transacción activa ({@link Propagation#MANDATORY}):
 * llamarlo fuera de una es un error de programación, no un evento perdido en silencio.
 *
 * <p>Arma el sobre de la versión 1 del contrato ({@code docs/events/schemas/envelope.v1.schema.json}) y le asigna la
 * siguiente secuencia de la solicitud. Todas las operaciones que publican ya tienen la solicitud bloqueada (ADR-043) o
 * la están creando, así que dos transacciones no pueden tomar el mismo número; la restricción única
 * {@code (aggregate_id, aggregate_sequence)} lo garantiza de todos modos.
 */
@Service
public class BusinessEventRecorder {

    static final String PRODUCER = "ms-services";
    static final String AGGREGATE_TYPE = "ServiceRequest";

    record Aggregate(String type, UUID id, long sequence) {
    }

    /** Sobre común del contrato. {@code correlationId} es siempre el id de la solicitud. */
    record Envelope(UUID eventId, String eventType, int schemaVersion, Instant occurredAt, String producer,
                    Aggregate aggregate, UUID correlationId, BusinessEvents.Actor actor, Object data) {
    }

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public BusinessEventRecorder(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Guarda el hecho como evento pendiente de publicar.
     *
     * @param serviceRequestId la solicitud a la que pertenece: agregado, clave del mensaje y correlación
     * @param occurredAt       el instante del hecho, el mismo con el que el dominio registró el cambio
     * @throws org.springframework.transaction.IllegalTransactionStateException si no hay una transacción activa
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(BusinessEvents.Event event, UUID serviceRequestId, BusinessEvents.Actor actor,
                              Instant occurredAt) {
        long sequence = repository.lastSequence(serviceRequestId) + 1;
        UUID eventId = UUID.randomUUID();
        Envelope envelope = new Envelope(eventId, event.type(), event.schemaVersion(), occurredAt, PRODUCER,
                new Aggregate(AGGREGATE_TYPE, serviceRequestId, sequence), serviceRequestId, actor, event.data());
        return repository.save(OutboxEvent.pending(eventId, event.type(), event.schemaVersion(), AGGREGATE_TYPE,
                serviceRequestId, sequence, serialize(envelope), occurredAt));
    }

    private String serialize(Envelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            // No debería pasar con estos registros; si pasa, la operación de negocio se revierte con el evento.
            throw new IllegalStateException("No se pudo serializar el evento " + envelope.eventType(), e);
        }
    }
}
