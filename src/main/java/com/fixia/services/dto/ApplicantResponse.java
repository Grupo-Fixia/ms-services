package com.fixia.services.dto;

import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ServiceRequestApplication;

import java.time.Instant;
import java.util.UUID;

/**
 * Postulante a una solicitud tal como lo ve el cliente dueño: el prestador (solo su id; el perfil público se consulta
 * aparte) y el estado de su postulación. No incluye datos de la solicitud ni ubicación alguna.
 */
public record ApplicantResponse(
        UUID id,
        UUID providerId,
        ApplicationStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public static ApplicantResponse from(ServiceRequestApplication application) {
        return new ApplicantResponse(
                application.getId(),
                application.getProviderId(),
                application.getStatus(),
                application.getCreatedAt(),
                application.getUpdatedAt());
    }
}
