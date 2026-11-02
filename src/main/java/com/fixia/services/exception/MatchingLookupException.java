package com.fixia.services.exception;

/**
 * No se pudieron obtener los técnicos compatibles de ms-matching-geo (no respondió, respondió un error o una respuesta
 * inválida). Falla cerrado: se responde 503 y nunca una lista vacía, que parecería que no hay técnicos.
 */
public class MatchingLookupException extends RuntimeException {

    public MatchingLookupException(String message, Throwable cause) {
        super(message, cause);
    }

    public MatchingLookupException(String message) {
        super(message);
    }
}
