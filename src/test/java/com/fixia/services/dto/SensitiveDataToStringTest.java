package com.fixia.services.dto;

import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** La dirección y la descripción del cliente no deben llegar a logs por un toString() accidental. */
class SensitiveDataToStringTest {

    @Test
    void laSolicitudDeCreacionNoImprimeDescripcionNiDirecciones() {
        CreateServiceRequestRequest request = new CreateServiceRequestRequest(ServiceCategory.PLUMBING,
                "Descripción privada", new ApproximateLocationRequest("Bogotá", "Chapinero", 4.6, -74.0),
                new ExactLocationRequest("Calle 10 # 5-20", 4.7, -74.1), Instant.now(), List.of("a.jpg"));

        assertThat(request.toString())
                .contains("PLUMBING")
                .doesNotContain("Descripción privada", "Calle 10", "Chapinero", "Bogotá", "a.jpg");
    }

    @Test
    void elPrecioPropuestoDeLaPostulacionNoSeImprime() {
        CreateApplicationRequest request = new CreateApplicationRequest(UUID.randomUUID(),
                new BigDecimal("187654.32"), CurrencyCode.COP);

        assertThat(request.toString()).doesNotContain("187654");
    }

    @Test
    void laDireccionExactaNoSeImprime() {
        assertThat(new ExactLocationRequest("Calle 10 # 5-20", 4.7, -74.1).toString())
                .doesNotContain("Calle 10");
    }
}
