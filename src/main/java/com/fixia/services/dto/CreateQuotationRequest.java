package com.fixia.services.dto;

import com.fixia.services.domain.CurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Cotización que el prestador seleccionado envía al cliente (GC-354). El prestador no viaja en el cuerpo: sale del
 * token. Las reglas entre fechas y frente al reloj las valida el dominio ({@code Quotation#send}).
 */
public record CreateQuotationRequest(
        @NotNull(message = "La solicitud es obligatoria")
        UUID serviceRequestId,

        @NotNull(message = "El valor es obligatorio")
        @Positive(message = "El valor debe ser mayor que cero")
        @Digits(integer = 10, fraction = 2, message = "El valor admite máximo 10 enteros y dos decimales")
        BigDecimal amount,

        @NotNull(message = "La moneda es obligatoria")
        CurrencyCode currency,

        @NotBlank(message = "El alcance es obligatorio")
        @Size(max = 2000, message = "El alcance no puede superar 2000 caracteres")
        String scope,

        @NotBlank(message = "Las condiciones son obligatorias")
        @Size(max = 2000, message = "Las condiciones no pueden superar 2000 caracteres")
        String conditions,

        @NotNull(message = "La fecha acordada es obligatoria")
        Instant agreedAt,

        @NotNull(message = "La vigencia es obligatoria")
        Instant validUntil
) {

    /** Valor, alcance y condiciones son datos restringidos (CD-06): no deben llegar a logs. */
    @Override
    public String toString() {
        return "CreateQuotationRequest[serviceRequestId=" + serviceRequestId + "]";
    }
}
