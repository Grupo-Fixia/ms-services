package com.fixia.services.exception;

/**
 * La solicitud no admite una cotización de este prestador en este momento: no es el prestador seleccionado, la
 * selección ya no está vigente o la solicitud ya tiene una cotización aceptada (GC-354).
 */
public class QuotationNotAllowedException extends RuntimeException {

    public QuotationNotAllowedException(String message) {
        super(message);
    }
}
