package com.fixia.services.relay;

import com.fixia.services.domain.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Implementación de {@link OutboxPublisher} que utiliza {@link KafkaTemplate} para publicar
 * los eventos hacia Apache Kafka según el contrato de GC-436.
 */
@Component
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaOutboxPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public CompletableFuture<Void> publish(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.getTopic(),
                event.getMessageKey(),
                event.getPayload()
        );

        record.headers().add(new RecordHeader("eventId",
                event.getEventId().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("eventType",
                event.getEventType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("schemaVersion",
                String.valueOf(event.getSchemaVersion()).getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("content-type",
                "application/json".getBytes(StandardCharsets.UTF_8)));

        return kafkaTemplate.send(record)
                .thenAccept(sendResult -> {
                    // Acuse de recibo confirmado por el broker de Kafka
                });
    }
}

