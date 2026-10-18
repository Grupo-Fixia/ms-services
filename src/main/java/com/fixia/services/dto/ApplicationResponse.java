package com.fixia.services.dto;

import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Postulación tal como la ve el técnico, con un resumen de la solicitud. Solo incluye la ubicación aproximada: la
 * dirección exacta no se entrega a ningún prestador antes de la contratación.
 */
public record ApplicationResponse(
        UUID id,
        ApplicationStatus status,
        Instant createdAt,
        Instant updatedAt,
        RequestSummary serviceRequest
) {

    public record RequestSummary(UUID id, ServiceCategory category, ServiceRequestStatus status, String city,
                                 String zone, Double latitude, Double longitude, Instant expectedAt) {

        static RequestSummary from(ServiceRequest request) {
            ApproximateLocation location = request.getApproximateLocation();
            return new RequestSummary(request.getId(), request.getCategory(), request.getStatus(), location.city(),
                    location.zone(), location.latitude(), location.longitude(), request.getExpectedAt());
        }
    }

    public static ApplicationResponse from(ServiceRequestApplication application, ServiceRequest request) {
        return new ApplicationResponse(
                application.getId(),
                application.getStatus(),
                application.getCreatedAt(),
                application.getUpdatedAt(),
                request == null ? null : RequestSummary.from(request));
    }
}
