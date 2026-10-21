package com.fixia.services.dto;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;

import java.time.Instant;
import java.util.UUID;

/**
 * Solicitud compatible tal como la ve un técnico (GC-337). Solo lleva la ubicación aproximada; no incluye al cliente,
 * la dirección exacta ni las fotos: nada de eso se entrega a un prestador antes de la contratación.
 */
public record OpportunityResponse(
        UUID id,
        ServiceCategory category,
        String description,
        String city,
        String zone,
        Double latitude,
        Double longitude,
        Instant expectedAt,
        Instant publishedAt
) {

    public static OpportunityResponse from(ServiceRequest request) {
        ApproximateLocation location = request.getApproximateLocation();
        return new OpportunityResponse(request.getId(), request.getCategory(), request.getDescription(),
                location.city(), location.zone(), location.latitude(), location.longitude(),
                request.getExpectedAt(), request.getPublishedAt());
    }
}
