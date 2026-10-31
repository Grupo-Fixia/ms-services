package com.fixia.services.exception;

/** El límite o el cursor de la consulta del historial no se pueden interpretar (400). Sin datos del cliente. */
public class InvalidHistoryQueryException extends RuntimeException {

    public InvalidHistoryQueryException(String message) {
        super(message);
    }
}
