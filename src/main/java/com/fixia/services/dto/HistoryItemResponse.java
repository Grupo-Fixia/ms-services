package com.fixia.services.dto;

import com.fixia.services.domain.ActorRole;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.domain.ServiceRequestTransition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un servicio del historial propio (GC-448): lo mínimo para listarlo y navegar a su detalle. {@code role} es cómo
 * participó quien consulta y {@code counterpartId}, la otra parte (el prestador para el cliente, el cliente para el
 * prestador). El detalle del prestador es {@code GET /api/services/contracts/{contractId}} y el del cliente,
 * {@code GET /api/services/requests/{serviceRequestId}/contract}.
 *
 * <p>No lleva la dirección exacta, datos de contacto, el motivo de una objeción ni evaluaciones (las evaluaciones viven
 * en ms-users). {@code budgetAmount} es el presupuesto orientativo del cliente y {@code contractedAmount} lo acordado,
 * que son datos distintos (RN-004). Las fechas del ciclo salen del historial de transiciones: {@code startedAt} es el
 * primer paso a ejecución, {@code finishedAt} la declaración de fin del técnico que sigue vigente (es {@code null} si el
 * cliente la objetó y el servicio volvió a ejecución) y {@code closedAt} el cierre. Un servicio anterior al historial
 * (o que aún no llegó a esa etapa) las trae en {@code null}: no se inventan.
 */
public record HistoryItemResponse(
        UUID serviceRequestId,
        UUID contractId,
        ActorRole role,
        UUID counterpartId,
        ServiceCategory category,
        ServiceRequestStatus status,
        BigDecimal budgetAmount,
        BigDecimal contractedAmount,
        CurrencyCode currency,
        Instant agreedAt,
        Instant contractedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant closedAt,
        Instant lastTransitionAt
) {

    /**
     * @param timeline historial de la solicitud, del más antiguo al más reciente; vacío si el servicio es anterior a él
     */
    public static HistoryItemResponse from(ServiceContract contract, ServiceRequest request, ActorRole role,
                                           List<ServiceRequestTransition> timeline) {
        UUID counterpart = role == ActorRole.CLIENT ? contract.getProviderId() : contract.getClientId();
        Instant startedAt = null;
        Instant finishedAt = null;
        Instant closedAt = null;
        Instant lastTransitionAt = null;
        for (ServiceRequestTransition transition : timeline) {
            lastTransitionAt = transition.getOccurredAt();
            switch (transition.getToStatus()) {
                case IN_PROGRESS -> {
                    if (startedAt == null) {
                        startedAt = transition.getOccurredAt();
                    }
                    finishedAt = null;
                }
                case TECHNICIAN_FINISHED -> finishedAt = transition.getOccurredAt();
                case CLOSED -> closedAt = transition.getOccurredAt();
                default -> {
                }
            }
        }
        return new HistoryItemResponse(
                request.getId(),
                contract.getId(),
                role,
                counterpart,
                request.getCategory(),
                request.getStatus(),
                request.getBudgetAmount(),
                contract.getAmount(),
                contract.getCurrency(),
                contract.getAgreedAt(),
                contract.getContractedAt(),
                startedAt,
                finishedAt,
                closedAt,
                lastTransitionAt);
    }
}
