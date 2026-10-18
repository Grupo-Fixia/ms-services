package com.fixia.services.domain;

/**
 * Dirección exacta del cliente. Dato restringido (PII): permanece protegida hasta que la solicitud pasa a
 * {@link ServiceRequestStatus#CONTRACTED}. Las coordenadas son opcionales (ambas o ninguna).
 */
public record ExactLocation(String address, Double latitude, Double longitude) {

    public ExactLocation(String address) {
        this(address, null, null);
    }

    /** Evita que la dirección llegue a logs o trazas por un {@code toString()} accidental. */
    @Override
    public String toString() {
        return "ExactLocation[protegida]";
    }
}
