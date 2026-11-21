package com.fixia.services.exception;

/** El servicio ya no admite mensajes nuevos (409): la conversación se puede leer, pero no escribir. */
public class ConversationClosedException extends RuntimeException {

    public ConversationClosedException(String message) {
        super(message);
    }
}
