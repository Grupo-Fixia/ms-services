package com.fixia.services.replication;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Clave de un estado replicado: su tipo ({@code tecnico.estado}, ...) y la entidad (GC-547). */
@Embeddable
public class StateKey implements Serializable {

    @Column(name = "state_type", nullable = false, length = 60, updatable = false)
    private String stateType;

    @Column(name = "state_key", nullable = false, updatable = false)
    private UUID stateKey;

    protected StateKey() {
    }

    public StateKey(String stateType, UUID stateKey) {
        this.stateType = stateType;
        this.stateKey = stateKey;
    }

    public String getStateType() {
        return stateType;
    }

    public UUID getStateKey() {
        return stateKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StateKey key && stateType.equals(key.stateType) && stateKey.equals(key.stateKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stateType, stateKey);
    }
}
