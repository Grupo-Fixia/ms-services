package com.fixia.services.client;

/**
 * No se pudo saber si un token fue revocado (ms-users no respondió, respondió un error o una respuesta
 * inválida). Quien la recibe debe tratar el token como no verificable.
 */
public class TokenRevocationCheckException extends RuntimeException {

    public TokenRevocationCheckException(String message, Throwable cause) {
        super(message, cause);
    }

    public TokenRevocationCheckException(String message) {
        super(message);
    }
}
