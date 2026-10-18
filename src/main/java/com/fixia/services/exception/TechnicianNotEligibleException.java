package com.fixia.services.exception;

import java.util.List;

/**
 * ms-users informó que el técnico no es elegible para la solicitud. Los motivos son códigos estables (por ejemplo
 * {@code CATEGORY}, {@code ZONE}, {@code AVAILABILITY}) para que el frontend explique qué le falta al técnico.
 */
public class TechnicianNotEligibleException extends RuntimeException {

    private final List<String> reasons;

    public TechnicianNotEligibleException(List<String> reasons) {
        super("No cumples las condiciones para postularte a esta solicitud");
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public List<String> getReasons() {
        return reasons;
    }
}
