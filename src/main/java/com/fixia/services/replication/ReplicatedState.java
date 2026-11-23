package com.fixia.services.replication;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Copia local del estado que otro servicio publicó de una entidad (GC-547). Solo se guarda la versión de mayor
 * secuencia; {@code removed} indica que la entidad ya no existe en su dueño.
 */
@Entity
@Table(name = "replicated_states")
public class ReplicatedState {

    @EmbeddedId
    private StateKey id;

    @Column(name = "sequence", nullable = false)
    private long sequence;

    @Column(name = "removed", nullable = false)
    private boolean removed;

    @Column(name = "data", nullable = false, columnDefinition = "TEXT")
    private String data;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected ReplicatedState() {
    }

    public ReplicatedState(StateKey id, long sequence, boolean removed, String data, Instant receivedAt) {
        this.id = id;
        this.sequence = sequence;
        this.removed = removed;
        this.data = data;
        this.receivedAt = receivedAt;
    }

    public void replace(long sequence, boolean removed, String data, Instant receivedAt) {
        this.sequence = sequence;
        this.removed = removed;
        this.data = data;
        this.receivedAt = receivedAt;
    }

    public StateKey getId() {
        return id;
    }

    public long getSequence() {
        return sequence;
    }

    public boolean isRemoved() {
        return removed;
    }

    public String getData() {
        return data;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
