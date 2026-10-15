package com.fixia.services.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Dirección exacta del cliente. Se guarda, pero no se entrega a nadie hasta que la solicitud esté contratada.
 */
public record ExactLocationRequest(
        @NotBlank(message = "La dirección exacta no puede estar vacía")
        @Size(max = 255, message = "La dirección exacta no puede superar 255 caracteres")
        String address,

        @DecimalMin(value = "-90.0", message = "La latitud debe estar entre -90 y 90")
        @DecimalMax(value = "90.0", message = "La latitud debe estar entre -90 y 90")
        Double latitude,

        @DecimalMin(value = "-180.0", message = "La longitud debe estar entre -180 y 180")
        @DecimalMax(value = "180.0", message = "La longitud debe estar entre -180 y 180")
        Double longitude
) {

    @JsonIgnore
    @AssertTrue(message = "La latitud y la longitud deben indicarse juntas")
    public boolean isCoordinatesComplete() {
        return (latitude == null) == (longitude == null);
    }

    /** Evita que la dirección llegue a logs o trazas por un {@code toString()} accidental. */
    @Override
    public String toString() {
        return "ExactLocationRequest[protegida]";
    }
}
