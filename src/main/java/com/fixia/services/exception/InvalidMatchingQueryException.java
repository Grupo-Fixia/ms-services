package com.fixia.services.exception;

/**
 * La consulta de técnicos compatibles no es válida: el límite fuera de rango o un cursor que ms-matching-geo no emitió.
 * Responde 400; el mensaje no repite el valor recibido.
 */
public class InvalidMatchingQueryException extends RuntimeException {

    public InvalidMatchingQueryException(String message) {
        super(message);
    }

    public static InvalidMatchingQueryException invalidCursor() {
        return new InvalidMatchingQueryException("El cursor no es válido");
    }
}
