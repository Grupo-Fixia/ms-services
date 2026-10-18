package com.fixia.services.exception;

import com.fixia.services.domain.QuotationStatus;

/**
 * Se intentó un cambio de estado que la cotización no permite: por ejemplo, aceptar una cotización rechazada,
 * reemplazada o vencida (RF-033).
 */
public class InvalidQuotationTransitionException extends RuntimeException {

    private final QuotationStatus from;
    private final QuotationStatus to;

    public InvalidQuotationTransitionException(QuotationStatus from, QuotationStatus to) {
        this(from, to, "No se puede pasar una cotización de " + from + " a " + to);
    }

    public InvalidQuotationTransitionException(QuotationStatus from, QuotationStatus to, String message) {
        super(message);
        this.from = from;
        this.to = to;
    }

    public QuotationStatus getFrom() {
        return from;
    }

    public QuotationStatus getTo() {
        return to;
    }
}
