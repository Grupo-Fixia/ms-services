package com.fixia.services.domain;

/**
 * Ubicación aproximada de la solicitud: es la única visible para los prestadores mientras no exista
 * contratación formalizada. Las coordenadas son opcionales (ambas o ninguna) y se redondean a dos decimales
 * (~1 km) al construir la solicitud, de modo que no permitan ubicar el domicilio.
 */
public record ApproximateLocation(String city, String zone, Double latitude, Double longitude) {

    public ApproximateLocation(String city, String zone) {
        this(city, zone, null, null);
    }
}
