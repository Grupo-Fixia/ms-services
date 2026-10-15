package com.fixia.services.exception;

/**
 * La solicitud no existe o no pertenece al cliente autenticado. Es la misma excepción en ambos casos, para
 * que la respuesta no permita averiguar qué solicitudes existen.
 */
public class ServiceRequestNotFoundException extends RuntimeException {

    public ServiceRequestNotFoundException() {
        super("No existe la solicitud indicada");
    }
}
