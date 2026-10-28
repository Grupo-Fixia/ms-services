package com.fixia.services.dto;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Solicitud compatible tal como la ve un técnico (GC-337). Solo lleva la ubicación aproximada; no incluye al cliente,
 * la dirección exacta ni las fotos: nada de eso se entrega a un prestador antes de la contratación. El presupuesto
 * orientativo del cliente (GC-421) es una guía para decidir si se postula, no un precio: llega en {@code null} en las
 * solicitudes anteriores a él.
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
        BigDecimal budgetAmount,
        CurrencyCode budgetCurrency,
        Instant publishedAt
) {

    public static OpportunityResponse from(ServiceRequest request) {
        ApproximateLocation location = request.getApproximateLocation();
        return new OpportunityResponse(request.getId(), request.getCategory(), request.getDescription(),
                location.city(), location.zone(), location.latitude(), location.longitude(),
                request.getExpectedAt(), request.getBudgetAmount(), request.getBudgetCurrency(),
                request.getPublishedAt());
    }
}
