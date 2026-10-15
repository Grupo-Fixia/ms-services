package com.fixia.services.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Ubicación aproximada de la solicitud: es la única que verán los prestadores antes de la contratación. */
public record ApproximateLocationRequest(
        @NotBlank(message = "La ciudad es obligatoria")
        @Size(max = 100, message = "La ciudad no puede superar 100 caracteres")
        String city,

        @NotBlank(message = "La zona es obligatoria")
        @Size(max = 100, message = "La zona no puede superar 100 caracteres")
        String zone,

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
}
