package com.fixia.services.exception;

/**
 * La postulación no existe o no es del técnico autenticado. Es la misma excepción en ambos casos, para que la
 * respuesta no permita averiguar qué postulaciones existen.
 */
public class ApplicationNotFoundException extends RuntimeException {

    public ApplicationNotFoundException() {
        super("No existe la postulación indicada");
    }
}
