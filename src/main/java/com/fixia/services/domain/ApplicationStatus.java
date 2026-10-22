package com.fixia.services.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estado de una postulación a una solicitud de servicio y sus transiciones permitidas.
 *
 * <pre>
 * APPLIED -> SELECTED, REJECTED
 * SELECTED y REJECTED son terminales
 * </pre>
 *
 * La postulación expresa interés y no asigna el trabajo (RN-002). La selección del prestador (GC-348) pasa una
 * postulación a {@code SELECTED} y las demás de la misma solicitud a {@code REJECTED}.
 */
public enum ApplicationStatus {

    /** El técnico se postuló y espera la decisión del cliente. */
    APPLIED,

    /** El cliente eligió a este prestador para la solicitud. */
    SELECTED,

    /** El cliente eligió a otro prestador, o la solicitud dejó de recibir postulaciones. */
    REJECTED;

    public Set<ApplicationStatus> allowedTransitions() {
        return switch (this) {
            case APPLIED -> EnumSet.of(SELECTED, REJECTED);
            case SELECTED, REJECTED -> EnumSet.noneOf(ApplicationStatus.class);
        };
    }

    public boolean canTransitionTo(ApplicationStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
