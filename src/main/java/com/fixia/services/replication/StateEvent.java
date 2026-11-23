package com.fixia.services.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Estado replicado tal como llega de Kafka (GC-547): tipo, entidad ({@code aggregate.id}), secuencia, si fue eliminada y
 * sus datos. El resto del sobre se ignora (lector tolerante).
 */
public record StateEvent(String stateType, UUID key, long sequence, boolean removed, JsonNode data) {

    /** @throws InvalidStateEventException si el mensaje no tiene la forma del contrato */
    public static StateEvent parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidStateEventException("El mensaje está vacío");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            // No se conserva la causa: el texto del error de Jackson cita un fragmento del mensaje.
            throw new InvalidStateEventException("El mensaje no es JSON válido");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidStateEventException("El mensaje no es un sobre de estado");
        }
        String type = root.path("eventType").asText(null);
        if (type == null || type.isBlank()) {
            throw new InvalidStateEventException("Falta eventType");
        }
        UUID key;
        try {
            key = UUID.fromString(root.path("aggregate").path("id").asText(""));
        } catch (IllegalArgumentException e) {
            throw new InvalidStateEventException("Falta aggregate.id o no es válido");
        }
        JsonNode sequence = root.path("aggregate").path("sequence");
        if (!sequence.isIntegralNumber() || sequence.asLong() < 1) {
            throw new InvalidStateEventException("Falta aggregate.sequence o no es válida");
        }
        boolean removed = root.path("removed").asBoolean(false);
        JsonNode data = root.path("data");
        if (!data.isObject()) {
            throw new InvalidStateEventException("Falta data");
        }
        return new StateEvent(type, key, sequence.asLong(), removed, data);
    }
}
