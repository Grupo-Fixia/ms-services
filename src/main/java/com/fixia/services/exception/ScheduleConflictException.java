package com.fixia.services.exception;

/**
 * El prestador ya tiene un servicio contratado y sin terminar a una hora que se cruza con la fecha acordada de esta
 * cotización. Un técnico no puede estar en dos servicios al mismo tiempo, así que ni se envía ni se acepta (409).
 */
public class ScheduleConflictException extends RuntimeException {

    public ScheduleConflictException(String message) {
        super(message);
    }
}
