package com.fixia.services.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Postulación a una solicitud (GC-342). El técnico no viaja en el cuerpo: sale del token. */
public record CreateApplicationRequest(
        @NotNull(message = "La solicitud es obligatoria")
        UUID serviceRequestId
) {
}
