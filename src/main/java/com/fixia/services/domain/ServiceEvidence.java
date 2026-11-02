package com.fixia.services.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadatos de una evidencia registrada para un servicio y su contrato (GC-439, RF-047, RD-11).
 * El archivo reside en RustFS (S3), nunca en la base de datos.
 */
@Entity
@Table(name = "service_evidences")
public class ServiceEvidence {

    @Id
    private UUID id;

    @Column(name = "service_request_id", nullable = false, updatable = false)
    private UUID serviceRequestId;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64, updatable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private EvidenceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ServiceEvidence() {
    }

    public ServiceEvidence(UUID id, UUID serviceRequestId, UUID contractId, UUID uploadedBy,
                           String objectKey, String contentType, long sizeBytes, String sha256,
                           EvidenceStatus status, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "El id es obligatorio");
        this.serviceRequestId = Objects.requireNonNull(serviceRequestId, "La solicitud es obligatoria");
        this.contractId = Objects.requireNonNull(contractId, "El contrato es obligatorio");
        this.uploadedBy = Objects.requireNonNull(uploadedBy, "El autor es obligatorio");
        this.objectKey = Objects.requireNonNull(objectKey, "La clave de objeto es obligatoria");
        this.contentType = Objects.requireNonNull(contentType, "El tipo de contenido es obligatorio");
        this.sizeBytes = sizeBytes;
        this.sha256 = Objects.requireNonNull(sha256, "El hash SHA-256 es obligatorio");
        this.status = Objects.requireNonNull(status, "El estado es obligatorio");
        this.createdAt = Objects.requireNonNull(createdAt, "La fecha de creación es obligatoria");
    }

    public static ServiceEvidence available(UUID id, UUID serviceRequestId, UUID contractId, UUID uploadedBy,
                                           String objectKey, String contentType, long sizeBytes, String sha256,
                                           Instant createdAt) {
        return new ServiceEvidence(id, serviceRequestId, contractId, uploadedBy, objectKey, contentType,
                sizeBytes, sha256, EvidenceStatus.AVAILABLE, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getServiceRequestId() {
        return serviceRequestId;
    }

    public UUID getContractId() {
        return contractId;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public EvidenceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ServiceEvidence that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return ServiceEvidence.class.hashCode();
    }
}

