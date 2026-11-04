package com.fixia.services.exception;

/** El almacenamiento de objetos no respondió o no pudo confirmar la operación (GC-439). */
public class StorageUnavailableException extends RuntimeException {
    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageUnavailableException(String message) {
        super(message);
    }
}
