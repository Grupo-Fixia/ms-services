package com.fixia.services.exception;

/** El mensaje o la consulta del chat no son válidos (400): texto vacío o largo, límite o cursor inválidos. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
