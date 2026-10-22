package com.fixia.services.exception;

/**
 * La solicitud existe pero ya no recibe postulaciones: no está publicada (ya tiene prestador, se canceló o venció)
 * o su momento esperado ya pasó.
 */
public class ServiceRequestNotAvailableException extends RuntimeException {

    public ServiceRequestNotAvailableException() {
        this("La solicitud ya no recibe postulaciones");
    }

    public ServiceRequestNotAvailableException(String message) {
        super(message);
    }
}
