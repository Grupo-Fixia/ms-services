package com.fixia.services.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Fotografía adjunta a una solicitud. Solo guarda la clave del objeto en el almacenamiento (S3/RustFS),
 * no el contenido ni una URL firmada.
 */
@Entity
@Table(name = "service_request_photos")
public class ServiceRequestPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_request_id", nullable = false)
    private ServiceRequest serviceRequest;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    @Column(name = "sort_order", nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ServiceRequestPhoto() {
    }

    ServiceRequestPhoto(ServiceRequest serviceRequest, String storageKey, int position, Instant now) {
        this.serviceRequest = serviceRequest;
        this.storageKey = storageKey;
        this.position = position;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public int getPosition() {
        return position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
