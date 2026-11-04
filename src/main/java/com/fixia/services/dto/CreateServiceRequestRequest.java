package com.fixia.services.dto;

import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Datos para publicar una solicitud de servicio (GC-296). El cliente no viaja en el cuerpo: sale del token.
 *
 * @param budgetAmount   presupuesto orientativo (GC-421): un valor único, obligatorio y mayor que cero, solo como guía
 *                       para el prestador (no es un pago ni un límite)
 * @param budgetCurrency moneda del presupuesto; por ahora solo {@code COP}
 * @param photoKeys      claves de las fotos ya subidas al almacenamiento (opcionales, máximo 5)
 */
public record CreateServiceRequestRequest(
        @NotNull(message = "La categoría es obligatoria")
        ServiceCategory category,

        @NotBlank(message = "La descripción es obligatoria")
        @Size(max = 2000, message = "La descripción no puede superar 2000 caracteres")
        String description,

        @NotNull(message = "La ubicación aproximada es obligatoria")
        @Valid
        ApproximateLocationRequest approximateLocation,

        @Valid
        ExactLocationRequest exactLocation,

        @NotNull(message = "El momento esperado es obligatorio")
        @Future(message = "El momento esperado debe ser posterior a la publicación")
        Instant expectedAt,

        @NotNull(message = "El presupuesto es obligatorio")
        @Positive(message = "El presupuesto debe ser mayor que cero")
        @Digits(integer = 10, fraction = 2, message = "El presupuesto admite máximo 10 enteros y dos decimales")
        BigDecimal budgetAmount,

        @NotNull(message = "La moneda del presupuesto es obligatoria")
        CurrencyCode budgetCurrency,

        @Size(max = 5, message = "Se permiten máximo 5 fotografías")
        List<@NotBlank(message = "La clave de la fotografía es obligatoria")
             @Size(max = 255, message = "La clave de la fotografía no puede superar 255 caracteres")
             @Pattern(regexp = "^(?!.*\\.\\.)[A-Za-z0-9][A-Za-z0-9._/-]*$",
                     message = "La clave de la fotografía tiene caracteres no permitidos") String> photoKeys
) {

    /** Evita que la dirección y la descripción lleguen a logs por un {@code toString()} accidental. */
    @Override
    public String toString() {
        return "CreateServiceRequestRequest[category=" + category + "]";
    }
}
