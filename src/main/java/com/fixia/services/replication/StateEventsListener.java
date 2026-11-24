package com.fixia.services.replication;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consume los tópicos de estado replicado que este servicio necesita ({@code fixia.replication.consumer.topics}, GC-547).
 * Lee desde el principio la primera vez y confirma el offset después de guardar (al menos una vez); si la base falla, el
 * manejador de errores del contenedor reintenta el mismo mensaje. Un mensaje inválido se registra, sin su contenido, y
 * se salta. Se apaga con {@code fixia.replication.consumer.enabled=false} (las pruebas lo apagan).
 */
@Component
@ConditionalOnProperty(prefix = "fixia.replication.consumer", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class StateEventsListener {

    private static final Logger log = LoggerFactory.getLogger(StateEventsListener.class);

    private final ReplicatedStateHandler handler;
    private final ObjectMapper objectMapper;

    public StateEventsListener(ReplicatedStateHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "replicated-state-listener",
            topics = "#{'${fixia.replication.consumer.topics}'.split(',')}",
            groupId = "${fixia.replication.consumer.group-id}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            StateEvent event = StateEvent.parse(objectMapper, record.value());
            ReplicatedStateHandler.Outcome outcome = handler.apply(event);
            log.debug("{} {} -> {}", event.stateType(), event.key(), outcome);
        } catch (InvalidStateEventException e) {
            log.error("Mensaje inválido en {}-{}@{}, se salta: {}", record.topic(), record.partition(),
                    record.offset(), e.getMessage());
        }
        acknowledgment.acknowledge();
    }
}
