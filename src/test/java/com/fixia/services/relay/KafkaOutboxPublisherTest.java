package com.fixia.services.relay;

import com.fixia.services.domain.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private KafkaOutboxPublisher publisher;

    @Test
    void publicaRecordConTopicoClavePayloadYCabecerasDelContrato() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(
                eventId,
                "solicitud.publicada",
                (short) 1,
                "ServiceRequest",
                requestId,
                1L,
                "{\"hello\":\"world\"}",
                Instant.now()
        );

        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        CompletableFuture<Void> result = publisher.publish(event);

        assertThat(result).isNotNull();
        assertThat(result.isDone()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo(event.getTopic());
        assertThat(record.key()).isEqualTo(requestId.toString());
        assertThat(record.value()).isEqualTo("{\"hello\":\"world\"}");

        Header eventIdHeader = record.headers().lastHeader("eventId");
        assertThat(eventIdHeader).isNotNull();
        assertThat(new String(eventIdHeader.value(), StandardCharsets.UTF_8)).isEqualTo(eventId.toString());

        Header eventTypeHeader = record.headers().lastHeader("eventType");
        assertThat(eventTypeHeader).isNotNull();
        assertThat(new String(eventTypeHeader.value(), StandardCharsets.UTF_8)).isEqualTo("solicitud.publicada");

        Header schemaVersionHeader = record.headers().lastHeader("schemaVersion");
        assertThat(schemaVersionHeader).isNotNull();
        assertThat(new String(schemaVersionHeader.value(), StandardCharsets.UTF_8)).isEqualTo("1");

        Header contentTypeHeader = record.headers().lastHeader("content-type");
        assertThat(contentTypeHeader).isNotNull();
        assertThat(new String(contentTypeHeader.value(), StandardCharsets.UTF_8)).isEqualTo("application/json");
    }
}

