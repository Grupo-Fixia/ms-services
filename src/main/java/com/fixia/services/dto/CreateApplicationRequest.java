package com.fixia.services.dto;

import com.fixia.services.domain.CurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Postulación a una solicitud (GC-342) con el precio que propone el técnico (GC-423). El técnico no viaja en el
 * cuerpo: sale del token.
 */
public record CreateApplicationRequest(
        @NotNull(message = "La solicitud es obligatoria")
        UUID serviceRequestId,

        @NotNull(message = "El precio propuesto es obligatorio")
        @Positive(message = "El precio propuesto debe ser mayor que cero")
        @Digits(integer = 10, fraction = 2, message = "El precio propuesto admite máximo 10 enteros y dos decimales")
        BigDecimal proposedAmount,

        @NotNull(message = "La moneda es obligatoria")
        CurrencyCode currency
) {

    /** El precio es un dato económico de la negociación: no debe llegar a logs. */
    @Override
    public String toString() {
        return "CreateApplicationRequest[serviceRequestId=" + serviceRequestId + "]";
    }
}
