package com.fixia.services.exception;

public class EvidenceNotFoundException extends RuntimeException {
    public EvidenceNotFoundException() {
        super("No existe la evidencia indicada");
    }

    public EvidenceNotFoundException(String message) {
        super(message);
    }
}

