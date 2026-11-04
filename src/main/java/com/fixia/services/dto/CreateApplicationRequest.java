package com.fixia.services.dto;

import com.fixia.services.domain.CurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Postulación a una solicitud (GC-342) con el precio que propone el prestador (GC-423). Quien se postula (técnico
 * independiente o empresa) no viaja en el cuerpo: sale del token. La empresa agrega el técnico que propone (GC-468).
 */
public record CreateApplicationRequest(
        @NotNull(message = "La solicitud es obligatoria")
        UUID serviceRequestId,

        @NotNull(message = "El precio propuesto es obligatorio")
        @Positive(message = "El precio propuesto debe ser mayor que cero")
        @Digits(integer = 10, fraction = 2, message = "El precio propuesto admite máximo 10 enteros y dos decimales")
        BigDecimal proposedAmount,

        @NotNull(message = "La moneda es obligatoria")
        CurrencyCode currency,

        /**
         * Técnico asociado que la empresa propone como ejecutor (GC-468). Obligatorio si se postula una empresa;
         * un técnico independiente no lo envía.
         */
        UUID technicianId
) {

    /** El precio es un dato económico de la negociación: no debe llegar a logs. */
    @Override
    public String toString() {
        return "CreateApplicationRequest[serviceRequestId=" + serviceRequestId + "]";
    }
}
