package com.fixia.services.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Guarda un estado replicado (GC-547). Una versión con secuencia menor o igual a la guardada llegó tarde o repetida y no
 * cambia nada: así las reentregas y los mensajes atrasados no deshacen un estado más nuevo. Tras guardar un cambio,
 * avisa a los {@link ReplicatedStateObserver} en la misma transacción (por ejemplo, la compensación de empresas).
 */
@Service
public class ReplicatedStateHandler {

    /** Qué se hizo con el estado. */
    public enum Outcome {
        APPLIED,
        STALE
    }

    private final ReplicatedStateRepository states;
    private final List<ReplicatedStateObserver> observers;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReplicatedStateHandler(ReplicatedStateRepository states, List<ReplicatedStateObserver> observers,
                                  ObjectMapper objectMapper, Clock clock) {
        this.states = states;
        this.observers = observers;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public Outcome apply(StateEvent event) {
        StateKey key = new StateKey(event.stateType(), event.key());
        Optional<ReplicatedState> current = states.findById(key);
        String data = event.data().toString();
        if (current.isEmpty()) {
            states.save(new ReplicatedState(key, event.sequence(), event.removed(), data, clock.instant()));
            notify(event, null, false);
            return Outcome.APPLIED;
        }
        if (current.get().getSequence() >= event.sequence()) {
            return Outcome.STALE;
        }
        JsonNode previous = read(current.get().getData());
        boolean previouslyRemoved = current.get().isRemoved();
        current.get().replace(event.sequence(), event.removed(), data, clock.instant());
        notify(event, previous, previouslyRemoved);
        return Outcome.APPLIED;
    }

    private void notify(StateEvent event, JsonNode previous, boolean previouslyRemoved) {
        for (ReplicatedStateObserver observer : observers) {
            observer.onChange(event, previous, previouslyRemoved);
        }
    }

    private JsonNode read(String data) {
        try {
            return objectMapper.readTree(data);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
