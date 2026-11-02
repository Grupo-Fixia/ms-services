package com.fixia.services.exception;

/** El contenido almacenado de una evidencia no coincide con el SHA-256 registrado (GC-439). */
public class EvidenceIntegrityException extends RuntimeException {
    public EvidenceIntegrityException(String message) {
        super(message);
    }
}
