package com.fixia.services.domain;

import com.fixia.services.exception.InvalidQuotationTransitionException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotationTest {

    private static final Instant NOW = Instant.parse("2026-10-16T15:00:00Z");
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final Instant AGREED = NOW.plus(Duration.ofDays(3));
    private static final Instant VALID_UNTIL = NOW.plus(Duration.ofDays(1));

    /** Solicitud con prestador seleccionado; el id lo asignaría la base al guardarla. */
    private static ServiceRequest selectedRequest() {
        ServiceRequest request = ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero"), null,
                NOW.plus(Duration.ofDays(5)), List.of(), NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        request.selectProvider(PROVIDER, NOW.minusSeconds(60));
        return request;
    }

    private static Quotation send(ServiceRequest request, UUID provider, String amount) {
        return Quotation.send(request, provider, new BigDecimal(amount), CurrencyCode.COP,
                "  Cambio de tubería y sellado  ", "  Incluye materiales; garantía de 3 meses  ", AGREED, VALID_UNTIL,
                NOW);
    }

    @Test
    void elPrestadorSeleccionadoEnviaUnaCotizacionConSusDatos() {
        ServiceRequest request = selectedRequest();

        Quotation quotation = send(request, PROVIDER, "350000");

        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.SENT);
        assertThat(quotation.getServiceRequestId()).isEqualTo(request.getId());
        assertThat(quotation.getProviderId()).isEqualTo(PROVIDER);
        assertThat(quotation.getAmount()).isEqualTo(new BigDecimal("350000.00"));
        assertThat(quotation.getCurrency()).isEqualTo(CurrencyCode.COP);
        assertThat(quotation.getScope()).isEqualTo("Cambio de tubería y sellado");
        assertThat(quotation.getConditions()).isEqualTo("Incluye materiales; garantía de 3 meses");
        assertThat(quotation.getAgreedAt()).isEqualTo(AGREED);
        assertThat(quotation.getValidUntil()).isEqualTo(VALID_UNTIL);
        assertThat(quotation.getRespondedAt()).isNull();
        assertThat(quotation.getCreatedAt()).isEqualTo(NOW);
        assertThat(quotation.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void soloElPrestadorSeleccionadoDeUnaSeleccionVigentePuedeCotizar() {
        assertThatThrownBy(() -> send(selectedRequest(), UUID.randomUUID(), "350000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Solo el prestador seleccionado puede cotizar esta solicitud");

        ServiceRequest published = ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, NOW.plus(Duration.ofDays(5)), List.of(),
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(published, "id", UUID.randomUUID());
        assertThatThrownBy(() -> send(published, PROVIDER, "350000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Solo se puede cotizar una solicitud con prestador seleccionado");

        ServiceRequest cancelled = selectedRequest();
        cancelled.cancel(NOW);
        assertThatThrownBy(() -> send(cancelled, PROVIDER, "350000")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> send(null, PROVIDER, "350000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> send(selectedRequest(), null, "350000")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elValorDebeSerPositivoConMaximoDosDecimalesYCaberEnLaBase() {
        assertThat(send(selectedRequest(), PROVIDER, "1250.50").getAmount()).isEqualTo(new BigDecimal("1250.50"));
        assertThat(send(selectedRequest(), PROVIDER, "1250.500").getAmount()).isEqualTo(new BigDecimal("1250.50"));
        assertThat(send(selectedRequest(), PROVIDER, "9999999999.99").getAmount())
                .isEqualTo(Quotation.MAX_AMOUNT);

        assertThatThrownBy(() -> send(selectedRequest(), PROVIDER, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> send(selectedRequest(), PROVIDER, "-10")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> send(selectedRequest(), PROVIDER, "10.001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El valor admite máximo dos decimales");
        assertThatThrownBy(() -> send(selectedRequest(), PROVIDER, "10000000000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Quotation.send(selectedRequest(), PROVIDER, null, CurrencyCode.COP, "a", "b",
                AGREED, VALID_UNTIL, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Quotation.send(selectedRequest(), PROVIDER, BigDecimal.TEN, null, "a", "b",
                AGREED, VALID_UNTIL, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elAlcanceYLasCondicionesSonObligatoriosYTienenLimite() {
        String tooLong = "x".repeat(Quotation.MAX_TEXT_LENGTH + 1);

        assertThatThrownBy(() -> Quotation.send(selectedRequest(), PROVIDER, BigDecimal.TEN, CurrencyCode.COP, " ",
                "b", AGREED, VALID_UNTIL, NOW)).hasMessage("El alcance es obligatorio");
        assertThatThrownBy(() -> Quotation.send(selectedRequest(), PROVIDER, BigDecimal.TEN, CurrencyCode.COP, "a",
                null, AGREED, VALID_UNTIL, NOW)).hasMessage("Las condiciones son obligatorias");
        assertThatThrownBy(() -> Quotation.send(selectedRequest(), PROVIDER, BigDecimal.TEN, CurrencyCode.COP,
                tooLong, "b", AGREED, VALID_UNTIL, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laFechaAcordadaYLaVigenciaDebenTenerSentido() {
        ServiceRequest request = selectedRequest();

        assertThatThrownBy(() -> Quotation.send(request, PROVIDER, BigDecimal.TEN, CurrencyCode.COP, "a", "b",
                null, VALID_UNTIL, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Quotation.send(request, PROVIDER, BigDecimal.TEN, CurrencyCode.COP, "a", "b",
                NOW, NOW, NOW)).hasMessage("La fecha acordada debe ser posterior al envío");
        assertThatThrownBy(() -> Quotation.send(request, PROVIDER, BigDecimal.TEN, CurrencyCode.COP, "a", "b",
                AGREED, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Quotation.send(request, PROVIDER, BigDecimal.TEN, CurrencyCode.COP, "a", "b",
                AGREED, NOW, NOW)).hasMessage("La vigencia debe ser posterior al envío");
        assertThatThrownBy(() -> Quotation.send(request, PROVIDER, BigDecimal.TEN, CurrencyCode.COP, "a", "b",
                AGREED, AGREED.plusSeconds(1), NOW))
                .hasMessage("La vigencia no puede ser posterior a la fecha acordada");
        assertThat(Quotation.send(request, PROVIDER, BigDecimal.TEN, CurrencyCode.COP, "a", "b", AGREED, AGREED, NOW)
                .getValidUntil()).isEqualTo(AGREED);
    }

    @Test
    void elClienteAceptaORechazaYQuedaRegistradoCuando() {
        Quotation accepted = send(selectedRequest(), PROVIDER, "350000");
        Instant later = NOW.plusSeconds(600);
        accepted.accept(later);
        assertThat(accepted.getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
        assertThat(accepted.getRespondedAt()).isEqualTo(later);
        assertThat(accepted.getUpdatedAt()).isEqualTo(later);

        Quotation rejected = send(selectedRequest(), PROVIDER, "350000");
        rejected.reject(later);
        assertThat(rejected.getStatus()).isEqualTo(QuotationStatus.REJECTED);
        assertThat(rejected.getRespondedAt()).isEqualTo(later);
    }

    @Test
    void unaCotizacionVencidaNoSePuedeAceptarYSeMarcaComoVencida() {
        Quotation quotation = send(selectedRequest(), PROVIDER, "350000");

        assertThat(quotation.isExpiredAt(VALID_UNTIL.minusSeconds(1))).isFalse();
        assertThat(quotation.isExpiredAt(VALID_UNTIL)).isTrue();
        assertThatThrownBy(() -> quotation.accept(VALID_UNTIL))
                .isInstanceOf(InvalidQuotationTransitionException.class)
                .hasMessage("La cotización está vencida y no se puede aceptar");
        assertThatThrownBy(() -> quotation.expire(VALID_UNTIL.minusSeconds(1)))
                .isInstanceOf(InvalidQuotationTransitionException.class)
                .hasMessage("La cotización todavía está vigente");

        quotation.expire(VALID_UNTIL);
        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.EXPIRED);
        assertThat(quotation.isExpiredAt(VALID_UNTIL.plusSeconds(60))).isFalse();
    }

    @Test
    void unaCotizacionReemplazadaRechazadaOAceptadaNoCambiaMas() {
        Quotation superseded = send(selectedRequest(), PROVIDER, "350000");
        superseded.supersede(NOW.plusSeconds(60));
        assertThat(superseded.getStatus()).isEqualTo(QuotationStatus.SUPERSEDED);

        InvalidQuotationTransitionException error = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidQuotationTransitionException.class, () -> superseded.accept(NOW.plusSeconds(120)));
        assertThat(error.getFrom()).isEqualTo(QuotationStatus.SUPERSEDED);
        assertThat(error.getTo()).isEqualTo(QuotationStatus.ACCEPTED);
        assertThatThrownBy(() -> superseded.reject(NOW.plusSeconds(120)))
                .isInstanceOf(InvalidQuotationTransitionException.class);
        assertThatThrownBy(() -> superseded.expire(VALID_UNTIL)).isInstanceOf(InvalidQuotationTransitionException.class);

        Quotation accepted = send(selectedRequest(), PROVIDER, "350000");
        accepted.accept(NOW.plusSeconds(60));
        assertThatThrownBy(() -> accepted.supersede(NOW.plusSeconds(120)))
                .isInstanceOf(InvalidQuotationTransitionException.class);
    }

    @Test
    void elTextoDeDepuracionNoIncluyeDatosRestringidosYLaIgualdadEsPorId() {
        Quotation quotation = send(selectedRequest(), PROVIDER, "350000");

        assertThat(quotation.toString()).contains("SENT").doesNotContain("350000").doesNotContain("tubería");
        assertThat(quotation).isNotEqualTo(send(selectedRequest(), PROVIDER, "350000")).isEqualTo(quotation)
                .isNotEqualTo("otra cosa");
        UUID id = UUID.randomUUID();
        Quotation copy = send(selectedRequest(), PROVIDER, "1");
        ReflectionTestUtils.setField(quotation, "id", id);
        ReflectionTestUtils.setField(copy, "id", id);
        assertThat(quotation).isEqualTo(copy).hasSameHashCodeAs(copy);
    }
}
