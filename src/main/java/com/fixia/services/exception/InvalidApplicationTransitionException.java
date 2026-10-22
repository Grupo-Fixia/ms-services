package com.fixia.services.exception;

import com.fixia.services.domain.ApplicationStatus;

/**
 * Se intentó un cambio de estado que la postulación no permite (por ejemplo, seleccionar una ya rechazada).
 */
public class InvalidApplicationTransitionException extends RuntimeException {

    private final ApplicationStatus from;
    private final ApplicationStatus to;

    public InvalidApplicationTransitionException(ApplicationStatus from, ApplicationStatus to) {
        super("No se puede pasar una postulación de " + from + " a " + to);
        this.from = from;
        this.to = to;
    }

    public ApplicationStatus getFrom() {
        return from;
    }

    public ApplicationStatus getTo() {
        return to;
    }
}
