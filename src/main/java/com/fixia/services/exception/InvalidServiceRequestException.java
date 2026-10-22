package com.fixia.services.exception;

/**
 * La solicitud no cumple una regla del dominio que la validación del cuerpo no alcanza a detectar. El mensaje
 * es propio del servicio (en español) y no incluye datos del cliente.
 */
public class InvalidServiceRequestException extends RuntimeException {

    public InvalidServiceRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
