package com.fixia.services.exception;

/**
 * La postulación no tiene la forma que corresponde al rol de quien se postula (GC-468): una empresa debe proponer un
 * técnico y un técnico independiente no propone a otro. Se responde 400.
 */
public class InvalidApplicationException extends RuntimeException {

    public InvalidApplicationException(String message) {
        super(message);
    }
}
