package com.fixia.services.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Lectura de los estados replicados de otros servicios (GC-547); los eliminados no se devuelven. */
@Component
public class ReplicatedStates {

    private final ReplicatedStateRepository states;
    private final ObjectMapper objectMapper;

    public ReplicatedStates(ReplicatedStateRepository states, ObjectMapper objectMapper) {
        this.states = states;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<JsonNode> find(String stateType, UUID key) {
        return states.findById(new StateKey(stateType, key))
                .filter(state -> !state.isRemoved())
                .map(this::read);
    }

    @Transactional(readOnly = true)
    public Map<UUID, JsonNode> findAll(String stateType, Collection<UUID> keys) {
        Map<UUID, JsonNode> found = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return found;
        }
        for (ReplicatedState state : states.findByIdStateTypeAndIdStateKeyInAndRemovedFalse(stateType, keys)) {
            found.put(state.getId().getStateKey(), read(state));
        }
        return found;
    }

    @Transactional(readOnly = true)
    public List<JsonNode> all(String stateType) {
        return states.findByIdStateTypeAndRemovedFalse(stateType).stream().map(this::read).toList();
    }

    private JsonNode read(ReplicatedState state) {
        try {
            return objectMapper.readTree(state.getData());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Estado replicado ilegible", e);
        }
    }
}
