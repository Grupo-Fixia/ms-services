package com.fixia.services.exception;

public class EvidenceLimitExceededException extends RuntimeException {
    public EvidenceLimitExceededException(String message) {
        super(message);
    }
}

