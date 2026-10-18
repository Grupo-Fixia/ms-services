package com.fixia.services.exception;

import java.util.UUID;

/**
 * El técnico ya se postuló a esta solicitud (GC-343). Lleva el id de la postulación existente cuando se conoce, para
 * que el frontend pueda mostrarla en lugar de un error genérico.
 */
public class DuplicateApplicationException extends RuntimeException {

    private final UUID existingApplicationId;

    public DuplicateApplicationException(UUID existingApplicationId) {
        super("Ya te postulaste a esta solicitud");
        this.existingApplicationId = existingApplicationId;
    }

    public UUID getExistingApplicationId() {
        return existingApplicationId;
    }
}
