package com.fixia.services.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Hecho de ms-payments leído de {@code fixia.payments.events} (contrato en {@code ms-payments/docs/events}). Solo se
 * interpreta lo que usa ms-services; el resto del sobre se ignora (lector tolerante, para que un campo nuevo en la
 * versión 1 no rompa el consumo).
 *
 * @param sequence {@code aggregate.sequence}: posición del hecho en la historia del pago, sin huecos ni repetidos
 * @param data     el cuerpo del hecho; cada tipo de evento tiene el suyo
 */
public record PaymentEvent(UUID eventId, String eventType, long sequence, JsonNode data) {

    public static final String PROCESSED = "pago.procesado";
    public static final String FAILED = "pago.fallido";

    /**
     * Lee el sobre tal como llega de Kafka.
     *
     * @throws InvalidPaymentEventException si el mensaje no tiene la forma del contrato
     */
    public static PaymentEvent parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidPaymentEventException("El mensaje está vacío");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            // No se conserva la causa: el texto del error de Jackson cita un fragmento del mensaje.
            throw new InvalidPaymentEventException("El mensaje no es JSON válido");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidPaymentEventException("El mensaje no es un sobre de evento");
        }
        UUID eventId = uuid(root, "eventId");
        String eventType = root.path("eventType").asText(null);
        if (eventType == null || eventType.isBlank()) {
            throw new InvalidPaymentEventException("Falta eventType");
        }
        JsonNode sequence = root.path("aggregate").path("sequence");
        if (!sequence.isIntegralNumber() || sequence.asLong() < 1) {
            throw new InvalidPaymentEventException("Falta aggregate.sequence o no es válida");
        }
        JsonNode data = root.path("data");
        if (!data.isObject()) {
            throw new InvalidPaymentEventException("Falta data");
        }
        return new PaymentEvent(eventId, eventType, sequence.asLong(), data);
    }

    /** ¿Es un hecho que decide si el servicio puede iniciar? Los demás de pagos no son asunto de ms-services. */
    public boolean decidesStart() {
        return PROCESSED.equals(eventType) || FAILED.equals(eventType);
    }

    public UUID dataUuid(String field) {
        return uuid(data, "data." + field, field);
    }

    /** Un importe del contrato: {@code {"amount": "105000.00", "currency": "COP"}} (el valor es texto). */
    public Money dataMoney(JsonNode node, String field) {
        JsonNode money = node.path(field);
        JsonNode amount = money.path("amount");
        JsonNode currency = money.path("currency");
        if (!amount.isTextual() || !currency.isTextual()) {
            throw new InvalidPaymentEventException(eventType + ": falta o no es válido " + field);
        }
        try {
            return new Money(new BigDecimal(amount.asText()), currency.asText());
        } catch (NumberFormatException e) {
            throw new InvalidPaymentEventException(eventType + ": falta o no es válido " + field);
        }
    }

    private static UUID uuid(JsonNode node, String field) {
        return uuid(node, field, field);
    }

    private static UUID uuid(JsonNode node, String label, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new InvalidPaymentEventException("Falta " + label + " o no es válido");
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentEventException("Falta " + label + " o no es válido");
        }
    }

    public record Money(BigDecimal amount, String currency) {
    }
}
