package com.fixia.services.consumer;

/**
 * El mensaje de {@code fixia.payments.events} no cumple el contrato (JSON roto o campos que faltan). Reintentarlo no lo
 * arregla: el consumidor lo registra, sin su contenido, y lo salta. El mensaje de esta excepción nunca incluye datos del
 * evento.
 */
public class InvalidPaymentEventException extends RuntimeException {

    public InvalidPaymentEventException(String message) {
        super(message);
    }

    public InvalidPaymentEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
