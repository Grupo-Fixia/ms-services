package com.fixia.services.replication;

/** Un mensaje de estado replicado que no cumple el contrato (GC-547): se registra, sin su contenido, y se salta. */
public class InvalidStateEventException extends RuntimeException {

    public InvalidStateEventException(String message) {
        super(message);
    }
}
