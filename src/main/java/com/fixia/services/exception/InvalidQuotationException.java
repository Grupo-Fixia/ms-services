package com.fixia.services.exception;

/**
 * La cotización no cumple una regla del dominio que la validación del cuerpo no alcanza a detectar (por ejemplo, la
 * vigencia frente a la fecha acordada o frente al reloj del servicio). El mensaje es propio del servicio.
 */
public class InvalidQuotationException extends RuntimeException {

    public InvalidQuotationException(String message, Throwable cause) {
        super(message, cause);
    }
}
