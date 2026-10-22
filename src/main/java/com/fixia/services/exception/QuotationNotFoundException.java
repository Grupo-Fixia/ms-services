package com.fixia.services.exception;

/**
 * La cotización no existe o no es del prestador autenticado. Es la misma excepción en ambos casos, para que la
 * respuesta no permita averiguar qué cotizaciones existen.
 */
public class QuotationNotFoundException extends RuntimeException {

    public QuotationNotFoundException() {
        super("No existe la cotización indicada");
    }
}
