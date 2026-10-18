package com.fixia.services.exception;

import com.fixia.services.domain.ServiceRequestStatus;

/**
 * Se intentó una transición de estado que la máquina de estados de la solicitud no permite.
 */
public class InvalidStatusTransitionException extends RuntimeException {

    private final ServiceRequestStatus from;
    private final ServiceRequestStatus to;

    public InvalidStatusTransitionException(ServiceRequestStatus from, ServiceRequestStatus to) {
        super("No se puede pasar una solicitud de " + from + " a " + to);
        this.from = from;
        this.to = to;
    }

    public ServiceRequestStatus getFrom() {
        return from;
    }

    public ServiceRequestStatus getTo() {
        return to;
    }
}
