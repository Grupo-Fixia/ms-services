package com.fixia.services.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consume {@code fixia.payments.events} (GC-490): el resultado del pago virtual de cada contratación que publica
 * ms-payments. La confirmación del offset es manual y llega solo después de guardar el efecto (al menos una vez): si la
 * base falla, la excepción sube al manejador de errores, que reintenta el mismo mensaje sin confirmarlo; los repetidos
 * no tienen efecto porque el manejador deduplica por {@code eventId}. Un mensaje que no cumple el contrato se registra,
 * sin su contenido, y se salta, porque reintentarlo no lo arregla.
 *
 * <p>Se apaga con {@code fixia.payments.consumer.enabled=false} (las pruebas lo apagan, como el relay del Outbox).
 */
@Component
@ConditionalOnProperty(prefix = "fixia.payments.consumer", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final PaymentEventHandler handler;
    private final ObjectMapper objectMapper;

    public PaymentEventsListener(PaymentEventHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "payment-events-listener",
            topics = "${fixia.payments.consumer.topic:fixia.payments.events}",
            groupId = "${fixia.payments.consumer.group-id:ms-services}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            PaymentEvent event = PaymentEvent.parse(objectMapper, record.value());
            PaymentEventHandler.Outcome outcome = handler.handle(event);
            log.debug("{} {} -> {}", event.eventType(), event.eventId(), outcome);
        } catch (InvalidPaymentEventException e) {
            // Solo dónde estaba el mensaje: nunca su contenido.
            log.error("Mensaje inválido en {}-{}@{}, se salta: {}", record.topic(), record.partition(),
                    record.offset(), e.getMessage());
        }
        acknowledgment.acknowledge();
    }
}
