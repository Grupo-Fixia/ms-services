package com.fixia.services.dto;

import com.fixia.services.domain.ServiceEvidence;

import java.time.Instant;
import java.util.UUID;

public record EvidenceResponse(
        UUID id,
        UUID serviceRequestId,
        UUID contractId,
        UUID uploadedBy,
        String contentType,
        long sizeBytes,
        String sha256,
        Instant createdAt
) {
    public static EvidenceResponse from(ServiceEvidence evidence) {
        return new EvidenceResponse(
                evidence.getId(),
                evidence.getServiceRequestId(),
                evidence.getContractId(),
                evidence.getUploadedBy(),
                evidence.getContentType(),
                evidence.getSizeBytes(),
                evidence.getSha256(),
                evidence.getCreatedAt()
        );
    }
}

