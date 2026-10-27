package com.fixia.services.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Objeción del cliente al cierre de un servicio (GC-441). El motivo es obligatorio: sin él no hay incidencia que tratar.
 * Puede traer datos personales, por eso no se imprime ni viaja en el evento.
 */
public record ClosureObjectionRequest(
        @NotBlank(message = "El motivo de la objeción es obligatorio")
        @Size(max = 500, message = "El motivo no puede superar 500 caracteres")
        String reason
) {

    @Override
    public String toString() {
        return "ClosureObjectionRequest[]";
    }
}
