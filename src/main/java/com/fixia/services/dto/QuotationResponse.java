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
 * @param revision   número de versión dentro de la solicitud (GC-427): 1 es la primera cotización, 2 el primer ajuste. Sirve
 *                   para mostrar «versión 2» sin calcularla
 * @param statusName nombre del estado en español, para mostrar ({@code status} es el código que se compara)
 * @param expired    la cotización sigue {@code SENT} pero su vigencia ya pasó: no se puede aceptar (RF-033)
 * @param current    es la versión vigente: sigue {@code SENT} y no ha vencido, es decir, la que el cliente aún puede
 *                   aceptar o rechazar. Una aceptada, rechazada, reemplazada o vencida no es vigente
 */
public record QuotationResponse(
        UUID id,
        UUID serviceRequestId,
        int revision,
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
        boolean current,
        Instant respondedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static QuotationResponse from(Quotation quotation, Instant now) {
        boolean expired = quotation.isExpiredAt(now);
        return new QuotationResponse(
                quotation.getId(),
                quotation.getServiceRequestId(),
                quotation.getRevision(),
                quotation.getProviderId(),
                quotation.getAmount(),
                quotation.getCurrency(),
                quotation.getScope(),
                quotation.getConditions(),
                quotation.getAgreedAt(),
                quotation.getValidUntil(),
                quotation.getStatus(),
                quotation.getStatus().getDisplayName(),
                expired,
                quotation.getStatus() == QuotationStatus.SENT && !expired,
                quotation.getRespondedAt(),
                quotation.getCreatedAt(),
                quotation.getUpdatedAt());
    }
}
