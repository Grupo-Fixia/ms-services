package com.fixia.services.replication;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** Lo último que este servicio publicó de una entidad (GC-547): hash del estado, secuencia y momento. */
@Entity
@Table(name = "state_publications")
public class StatePublication {

    @EmbeddedId
    private StateKey id;

    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    @Column(name = "sequence", nullable = false)
    private long sequence;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    protected StatePublication() {
    }

    public StatePublication(StateKey id, String hash, long sequence, Instant publishedAt) {
        this.id = id;
        this.hash = hash;
        this.sequence = sequence;
        this.publishedAt = publishedAt;
    }

    public void update(String hash, long sequence, Instant publishedAt) {
        this.hash = hash;
        this.sequence = sequence;
        this.publishedAt = publishedAt;
    }

    public StateKey getId() {
        return id;
    }

    public String getHash() {
        return hash;
    }

    public long getSequence() {
        return sequence;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
