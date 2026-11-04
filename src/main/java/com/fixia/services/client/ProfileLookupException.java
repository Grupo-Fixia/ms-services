package com.fixia.services.client;

/**
 * No se pudo obtener el perfil público de los técnicos (ms-users no respondió, respondió un error o una respuesta
 * inválida). Quien la recibe no debe mostrar postulantes con perfiles inventados ni a medias: responde 503.
 */
public class ProfileLookupException extends RuntimeException {

    public ProfileLookupException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProfileLookupException(String message) {
        super(message);
    }
}
