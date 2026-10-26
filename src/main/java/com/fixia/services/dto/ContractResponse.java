package com.fixia.services.dto;

import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.dto.ServiceRequestResponse.ApproximateLocationResponse;
import com.fixia.services.dto.ServiceRequestResponse.ExactLocationResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Contratación formalizada (GC-359): lo acordado, copiado de la cotización aceptada, y un resumen de la solicitud. Como
 * la solicitud ya está contratada, el resumen incluye la dirección exacta (si el cliente la dio), que es lo que recibe
 * el prestador (ADR-009). No hay datos de contacto ni de pago: el pago ocurre fuera de Fixia.
 */
public record ContractResponse(
        UUID id,
        UUID serviceRequestId,
        UUID quotationId,
        UUID clientId,
        UUID providerId,
        BigDecimal amount,
        CurrencyCode currency,
        String scope,
        String conditions,
        Instant agreedAt,
        Instant contractedAt,
        RequestSummary serviceRequest
) {

    public record RequestSummary(
            ServiceCategory category,
            String description,
            ServiceRequestStatus status,
            ApproximateLocationResponse approximateLocation,
            @JsonInclude(JsonInclude.Include.NON_NULL) ExactLocationResponse exactLocation,
            Instant expectedAt
    ) {
        static RequestSummary from(ServiceRequest request) {
            return new RequestSummary(
                    request.getCategory(),
                    request.getDescription(),
                    request.getStatus(),
                    ApproximateLocationResponse.from(request.getApproximateLocation()),
                    request.getExactLocation().map(ExactLocationResponse::from).orElse(null),
                    request.getExpectedAt());
        }
    }

    public static ContractResponse from(ServiceContract contract, ServiceRequest request) {
        return new ContractResponse(
                contract.getId(),
                contract.getServiceRequestId(),
                contract.getQuotationId(),
                contract.getClientId(),
                contract.getProviderId(),
                contract.getAmount(),
                contract.getCurrency(),
                contract.getScope(),
                contract.getConditions(),
                contract.getAgreedAt(),
                contract.getContractedAt(),
                RequestSummary.from(request));
    }
}
