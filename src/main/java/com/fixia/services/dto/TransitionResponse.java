package com.fixia.services.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fixia.services.domain.ActorRole;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.domain.ServiceRequestTransition;

import java.time.Instant;
import java.util.UUID;

/**
 * Un cambio de estado del servicio (GC-437): de dónde a dónde, quién lo hizo y cuándo. {@code fromStatus} llega en
 * {@code null} en la publicación; {@code actorId} y {@code reason} solo aparecen cuando existen.
 */
public record TransitionResponse(
        ServiceRequestStatus fromStatus,
        ServiceRequestStatus toStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID actorId,
        ActorRole actorRole,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason,
        Instant occurredAt
) {

    public static TransitionResponse from(ServiceRequestTransition transition) {
        return new TransitionResponse(transition.getFromStatus(), transition.getToStatus(), transition.getActorId(),
                transition.getActorRole(), transition.getReason(), transition.getOccurredAt());
    }
}
