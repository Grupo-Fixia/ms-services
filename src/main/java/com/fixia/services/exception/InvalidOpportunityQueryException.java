package com.fixia.services.exception;

/** El límite o el cursor de la consulta de oportunidades no se pueden interpretar (400). Sin datos del cliente. */
public class InvalidOpportunityQueryException extends RuntimeException {

    public InvalidOpportunityQueryException(String message) {
        super(message);
    }
}
