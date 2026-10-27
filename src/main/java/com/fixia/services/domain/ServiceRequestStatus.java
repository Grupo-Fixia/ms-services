package com.fixia.services.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estado de una solicitud de servicio y sus transiciones permitidas.
 *
 * <pre>
 * PUBLISHED         -> PROVIDER_SELECTED, CANCELLED, EXPIRED
 * PROVIDER_SELECTED -> CONTRACTED, CANCELLED
 * CONTRACTED        -> IN_PROGRESS
 * IN_PROGRESS, CANCELLED, EXPIRED no tienen más transiciones por ahora
 * </pre>
 *
 * <p>La finalización por el técnico y el cierre por el cliente (GC-441) son las siguientes etapas de {@code IN_PROGRESS}.
 */
public enum ServiceRequestStatus {

    /** Publicada por el cliente y visible para prestadores compatibles. Las postulaciones se modelan aparte. */
    PUBLISHED,

    /** El cliente eligió un único prestador; la selección queda cerrada frente a los demás postulantes. */
    PROVIDER_SELECTED,

    /** Contratación formalizada: a partir de aquí la dirección exacta deja de estar protegida. */
    CONTRACTED,

    /** El prestador contratado inició el servicio (GC-437). La dirección exacta sigue disponible para los dos. */
    IN_PROGRESS,

    CANCELLED,

    /** Venció el momento esperado sin que se formalizara la contratación. */
    EXPIRED;

    public Set<ServiceRequestStatus> allowedTransitions() {
        return switch (this) {
            case PUBLISHED -> EnumSet.of(PROVIDER_SELECTED, CANCELLED, EXPIRED);
            case PROVIDER_SELECTED -> EnumSet.of(CONTRACTED, CANCELLED);
            case CONTRACTED -> EnumSet.of(IN_PROGRESS);
            case IN_PROGRESS, CANCELLED, EXPIRED -> EnumSet.noneOf(ServiceRequestStatus.class);
        };
    }

    public boolean canTransitionTo(ServiceRequestStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    /**
     * Si desde este estado el prestador contratado y el cliente reciben la dirección exacta (ADR-009). Es el único lugar
     * que decide esto: cada etapa nueva posterior a la contratación se agrega aquí y no en cada consulta.
     */
    public boolean exposesExactLocation() {
        return this == CONTRACTED || this == IN_PROGRESS;
    }
}
