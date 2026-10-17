package com.fixia.services.client;

/**
 * No se pudo saber si el técnico es elegible (ms-users no respondió, respondió un error o una respuesta inválida).
 * Quien la recibe no debe registrar la postulación: falla cerrado, igual que la consulta de revocación.
 */
public class EligibilityCheckException extends RuntimeException {

    public EligibilityCheckException(String message, Throwable cause) {
        super(message, cause);
    }

    public EligibilityCheckException(String message) {
        super(message);
    }
}
