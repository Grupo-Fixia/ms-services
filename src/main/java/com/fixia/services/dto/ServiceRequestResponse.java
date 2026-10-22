package com.fixia.services.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Solicitud tal como la ve su cliente. La dirección exacta solo aparece cuando la solicitud ya está
 * contratada ({@link ServiceRequest#getExactLocation()}); antes no se entrega a nadie.
 */
public record ServiceRequestResponse(
        UUID id,
        ServiceCategory category,
        String description,
        ServiceRequestStatus status,
        ApproximateLocationResponse approximateLocation,
        @JsonInclude(JsonInclude.Include.NON_NULL) ExactLocationResponse exactLocation,
        Instant expectedAt,
        List<String> photoKeys,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID selectedProviderId,
        Instant publishedAt,
        Instant updatedAt
) {

    public record ApproximateLocationResponse(String city, String zone, Double latitude, Double longitude) {

        static ApproximateLocationResponse from(ApproximateLocation location) {
            return new ApproximateLocationResponse(location.city(), location.zone(),
                    location.latitude(), location.longitude());
        }
    }

    public record ExactLocationResponse(String address, Double latitude, Double longitude) {

        static ExactLocationResponse from(ExactLocation location) {
            return new ExactLocationResponse(location.address(), location.latitude(), location.longitude());
        }
    }

    public static ServiceRequestResponse from(ServiceRequest request) {
        return new ServiceRequestResponse(
                request.getId(),
                request.getCategory(),
                request.getDescription(),
                request.getStatus(),
                ApproximateLocationResponse.from(request.getApproximateLocation()),
                request.getExactLocation().map(ExactLocationResponse::from).orElse(null),
                request.getExpectedAt(),
                request.getPhotoKeys(),
                request.getSelectedProviderId(),
                request.getPublishedAt(),
                request.getUpdatedAt());
    }
}
