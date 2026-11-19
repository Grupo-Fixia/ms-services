package com.fixia.services.exception;

/** Se reutilizó el identificador de un mensaje ya enviado con otro texto (409). */
public class MessageConflictException extends RuntimeException {

    public MessageConflictException(String message) {
        super(message);
    }
}
