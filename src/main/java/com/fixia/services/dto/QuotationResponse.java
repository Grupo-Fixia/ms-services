package com.fixia.services.dto;

import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Cotización tal como la ven el prestador que la envió y el cliente dueño de la solicitud.
 *
 * @param statusName nombre del estado en español, para mostrar ({@code status} es el código que se compara)
 * @param expired    la cotización sigue {@code SENT} pero su vigencia ya pasó: no se puede aceptar (RF-033)
 */
public record QuotationResponse(
        UUID id,
        UUID serviceRequestId,
        UUID providerId,
        BigDecimal amount,
        CurrencyCode currency,
        String scope,
        String conditions,
        Instant agreedAt,
        Instant validUntil,
        QuotationStatus status,
        String statusName,
        boolean expired,
        Instant respondedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static QuotationResponse from(Quotation quotation, Instant now) {
        return new QuotationResponse(
                quotation.getId(),
                quotation.getServiceRequestId(),
                quotation.getProviderId(),
                quotation.getAmount(),
                quotation.getCurrency(),
                quotation.getScope(),
                quotation.getConditions(),
                quotation.getAgreedAt(),
                quotation.getValidUntil(),
                quotation.getStatus(),
                quotation.getStatus().getDisplayName(),
                quotation.isExpiredAt(now),
                quotation.getRespondedAt(),
                quotation.getCreatedAt(),
                quotation.getUpdatedAt());
    }
}
