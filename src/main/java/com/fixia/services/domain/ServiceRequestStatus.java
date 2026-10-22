package com.fixia.services.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estado de una solicitud de servicio y sus transiciones permitidas.
 *
 * <pre>
 * PUBLISHED         -> PROVIDER_SELECTED, CANCELLED, EXPIRED
 * PROVIDER_SELECTED -> CONTRACTED, CANCELLED
 * CONTRACTED, CANCELLED, EXPIRED son terminales
 * </pre>
 */
public enum ServiceRequestStatus {

    /** Publicada por el cliente y visible para prestadores compatibles. Las postulaciones se modelan aparte. */
    PUBLISHED,

    /** El cliente eligió un único prestador; la selección queda cerrada frente a los demás postulantes. */
    PROVIDER_SELECTED,

    /** Contratación formalizada: a partir de aquí la dirección exacta deja de estar protegida. */
    CONTRACTED,

    CANCELLED,

    /** Venció el momento esperado sin que se formalizara la contratación. */
    EXPIRED;

    public Set<ServiceRequestStatus> allowedTransitions() {
        return switch (this) {
            case PUBLISHED -> EnumSet.of(PROVIDER_SELECTED, CANCELLED, EXPIRED);
            case PROVIDER_SELECTED -> EnumSet.of(CONTRACTED, CANCELLED);
            case CONTRACTED, CANCELLED, EXPIRED -> EnumSet.noneOf(ServiceRequestStatus.class);
        };
    }

    public boolean canTransitionTo(ServiceRequestStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
