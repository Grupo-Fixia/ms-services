package com.fixia.services.domain;

import com.fixia.services.exception.InvalidStatusTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reglas del modelo de la solicitud: publicación, máquina de estados y protección de la dirección exacta.
 * Son pruebas de dominio puro, sin Spring ni base de datos.
 */
class ServiceRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant TOMORROW = NOW.plus(Duration.ofDays(1));
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final ExactLocation EXACT = new ExactLocation("Calle 10 # 5-20, apto 301", 4.7110, -74.0721);

    private static ServiceRequest published() {
        return ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero", 4.6533, -74.0836), EXACT, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW);
    }

    // ---- Publicación ----

    @Test
    void publicarCreaLaSolicitudEnEstadoPublicada() {
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.ELECTRICAL, "  Cambiar tomacorrientes  ",
                new ApproximateLocation(" Medellín ", " El Poblado "), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP,
                List.of("requests/a.jpg", "requests/b.jpg"), NOW);

        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.PUBLISHED);
        assertThat(request.getClientId()).isEqualTo(CLIENT);
        assertThat(request.getCategory()).isEqualTo(ServiceCategory.ELECTRICAL);
        assertThat(request.getDescription()).isEqualTo("Cambiar tomacorrientes");
        assertThat(request.getApproximateLocation().city()).isEqualTo("Medellín");
        assertThat(request.getApproximateLocation().zone()).isEqualTo("El Poblado");
        assertThat(request.getExpectedAt()).isEqualTo(TOMORROW);
        assertThat(request.getPhotoKeys()).containsExactly("requests/a.jpg", "requests/b.jpg");
        assertThat(request.getSelectedProviderId()).isNull();
        assertThat(request.getPublishedAt()).isEqualTo(NOW);
        assertThat(request.getCreatedAt()).isEqualTo(NOW);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void publicarSinFotosNiDireccionExactaEsValido() {
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.PAINTING, "Pintar sala",
                new ApproximateLocation("Cali", "Granada"), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, null, NOW);

        assertThat(request.getPhotoKeys()).isEmpty();
        assertThat(request.getApproximateLocation().latitude()).isNull();
        assertThat(request.getExactLocation()).isEmpty();
    }

    // ---- Presupuesto orientativo (GC-421) ----

    private static ServiceRequest publishWithBudget(BigDecimal amount, CurrencyCode currency) {
        return ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero"), null, TOMORROW, amount, currency, List.of(), NOW);
    }

    @Test
    void elPresupuestoSeGuardaConDosDecimalesYSuMoneda() {
        ServiceRequest request = publishWithBudget(new BigDecimal("185000.5"), CurrencyCode.COP);

        assertThat(request.getBudgetAmount()).isEqualTo(new BigDecimal("185000.50"));
        assertThat(request.getBudgetAmount().scale()).isEqualTo(2);
        assertThat(request.getBudgetCurrency()).isEqualTo(CurrencyCode.COP);
    }

    @Test
    void unPresupuestoEnEnteroYElMaximoPermitidoSonValidos() {
        assertThat(publishWithBudget(new BigDecimal("200000"), CurrencyCode.COP).getBudgetAmount())
                .isEqualTo(new BigDecimal("200000.00"));
        assertThat(publishWithBudget(new BigDecimal("9999999999.99"), CurrencyCode.COP).getBudgetAmount())
                .isEqualTo(new BigDecimal("9999999999.99"));
        // Ceros a la derecha no son decimales de más.
        assertThat(publishWithBudget(new BigDecimal("1000.1000"), CurrencyCode.COP).getBudgetAmount())
                .isEqualTo(new BigDecimal("1000.10"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01"})
    void elPresupuestoDebeSerMayorQueCero(String amount) {
        assertThatThrownBy(() -> publishWithBudget(new BigDecimal(amount), CurrencyCode.COP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El presupuesto debe ser mayor que cero");
    }

    @Test
    void elPresupuestoNoAdmiteMasDeDosDecimalesNiSuperarElMaximo() {
        assertThatThrownBy(() -> publishWithBudget(new BigDecimal("1000.001"), CurrencyCode.COP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El presupuesto admite máximo dos decimales");
        assertThatThrownBy(() -> publishWithBudget(new BigDecimal("10000000000"), CurrencyCode.COP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El presupuesto no puede superar 9999999999.99");
    }

    @Test
    void elPresupuestoYSuMonedaSonObligatorios() {
        assertThatThrownBy(() -> publishWithBudget(null, CurrencyCode.COP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El presupuesto es obligatorio");
        assertThatThrownBy(() -> publishWithBudget(new BigDecimal("200000"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La moneda del presupuesto es obligatoria");
    }

    @Test
    void lasCoordenadasAproximadasSeRedondeanADosDecimales() {
        ServiceRequest request = published();

        assertThat(request.getApproximateLocation().latitude()).isEqualTo(4.65);
        assertThat(request.getApproximateLocation().longitude()).isEqualTo(-74.08);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void publicarSinDescripcionFalla(String description) {
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, description,
                new ApproximateLocation("Bogotá", "Chapinero"), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("descripción");
    }

    @Test
    void publicarConDescripcionDemasiadoLargaFalla() {
        String tooLong = "x".repeat(ServiceRequest.MAX_DESCRIPTION_LENGTH + 1);

        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, tooLong,
                new ApproximateLocation("Bogotá", "Chapinero"), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2000");
    }

    @Test
    void publicarSinClienteFalla() {
        assertThatThrownBy(() -> ServiceRequest.publish(null, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cliente");
    }

    @Test
    void publicarSinCategoriaFalla() {
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, null, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categoría");
    }

    @Test
    void publicarSinUbicacionAproximadaFalla() {
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                null, null, TOMORROW, new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ubicación aproximada");
    }

    @Test
    void publicarSinCiudadOSinZonaFalla() {
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation(" ", "Chapinero"), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ciudad");
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", null), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zona");
    }

    @Test
    void publicarConCoordenadasIncompletasOFueraDeRangoFalla() {
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero", 4.6, null), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completas");
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero", 91.0, 0.0), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitud");
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero", 0.0, 181.0), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longitud");
    }

    @Test
    void publicarConDireccionExactaVaciaOCoordenadasInvalidasFalla() {
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), new ExactLocation("  "), TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dirección exacta");
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), new ExactLocation("Calle 1", 4.0, null),
                TOMORROW, new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exacta");
    }

    @Test
    void publicarSinMomentoEsperadoOEnElPasadoFalla() {
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, null,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("momento esperado");
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, NOW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("posterior");
    }

    @Test
    void publicarConMasFotosDelMaximoFalla() {
        List<String> tooMany = java.util.stream.IntStream.rangeClosed(1, ServiceRequest.MAX_PHOTOS + 1)
                .mapToObj(i -> "requests/" + i + ".jpg").toList();

        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, tooMany, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fotograf");
    }

    @Test
    void publicarConUnaClaveDeFotoVaciaFalla() {
        assertThatThrownBy(() -> ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of("ok.jpg", " "), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fotografía");
    }

    // ---- Transiciones ----

    @Test
    void elFlujoCompletoHastaLaContratacionRegistraPrestadorYFecha() {
        ServiceRequest request = published();
        Instant selectedAt = NOW.plusSeconds(60);
        Instant contractedAt = NOW.plusSeconds(120);

        request.selectProvider(PROVIDER, selectedAt);
        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
        assertThat(request.getSelectedProviderId()).isEqualTo(PROVIDER);
        assertThat(request.getUpdatedAt()).isEqualTo(selectedAt);

        request.contract(contractedAt);
        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(request.getSelectedProviderId()).isEqualTo(PROVIDER);
        assertThat(request.getUpdatedAt()).isEqualTo(contractedAt);
    }

    @Test
    void seleccionarPrestadorSinIndicarloFalla() {
        ServiceRequest request = published();

        assertThatThrownBy(() -> request.selectProvider(null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prestador");
        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.PUBLISHED);
    }

    @Test
    void unaSolicitudPublicadaPuedeCancelarseOExpirar() {
        ServiceRequest cancelled = published();
        cancelled.cancel(NOW.plusSeconds(1));
        assertThat(cancelled.getStatus()).isEqualTo(ServiceRequestStatus.CANCELLED);

        ServiceRequest expired = published();
        expired.expire(NOW.plusSeconds(1));
        assertThat(expired.getStatus()).isEqualTo(ServiceRequestStatus.EXPIRED);
    }

    @Test
    void unaSolicitudConPrestadorSeleccionadoPuedeCancelarse() {
        ServiceRequest request = published();
        request.selectProvider(PROVIDER, NOW);

        request.cancel(NOW.plusSeconds(1));

        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.CANCELLED);
    }

    @Test
    void noSePuedeContratarSinSeleccionarPrestador() {
        ServiceRequest request = published();

        assertThatThrownBy(() -> request.contract(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("PUBLISHED")
                .hasMessageContaining("CONTRACTED");
        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.PUBLISHED);
    }

    @Test
    void noSePuedeSeleccionarDosVecesNiExpirarConPrestadorSeleccionado() {
        ServiceRequest request = published();
        request.selectProvider(PROVIDER, NOW);

        assertThatThrownBy(() -> request.selectProvider(UUID.randomUUID(), NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> request.expire(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThat(request.getSelectedProviderId()).isEqualTo(PROVIDER);
    }

    @Test
    void unaSolicitudContratadaPuedeIniciarseYPasaAEnCurso() {
        ServiceRequest request = published();
        request.selectProvider(PROVIDER, NOW);
        request.contract(NOW);

        request.start(NOW.plusSeconds(60));

        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.IN_PROGRESS);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(request.getSelectedProviderId()).isEqualTo(PROVIDER);
    }

    @Test
    void soloSePuedeIniciarDesdeContratada() {
        ServiceRequest published = published();
        ServiceRequest selected = published();
        selected.selectProvider(PROVIDER, NOW);
        ServiceRequest started = published();
        started.selectProvider(PROVIDER, NOW);
        started.contract(NOW);
        started.start(NOW);

        for (ServiceRequest request : List.of(published, selected, started)) {
            ServiceRequestStatus before = request.getStatus();
            assertThatThrownBy(() -> request.start(NOW.plusSeconds(1)))
                    .isInstanceOf(InvalidStatusTransitionException.class)
                    .hasMessageContaining("IN_PROGRESS");
            assertThat(request.getStatus()).isEqualTo(before);
        }
    }

    @Test
    void losEstadosSinSiguienteEtapaNoPermitenNingunCambio() {
        ServiceRequest inProgress = published();
        inProgress.selectProvider(PROVIDER, NOW);
        inProgress.contract(NOW);
        inProgress.start(NOW);
        ServiceRequest cancelled = published();
        cancelled.cancel(NOW);
        ServiceRequest expired = published();
        expired.expire(NOW);

        for (ServiceRequest terminal : List.of(inProgress, cancelled, expired)) {
            ServiceRequestStatus before = terminal.getStatus();
            assertThatThrownBy(() -> terminal.cancel(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
            assertThatThrownBy(() -> terminal.expire(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
            assertThatThrownBy(() -> terminal.contract(NOW)).isInstanceOf(InvalidStatusTransitionException.class);
            assertThatThrownBy(() -> terminal.selectProvider(PROVIDER, NOW))
                    .isInstanceOf(InvalidStatusTransitionException.class);
            assertThat(terminal.getStatus()).isEqualTo(before);
        }
    }

    @Test
    void laExcepcionDeTransicionIndicaOrigenYDestino() {
        ServiceRequest request = published();

        assertThatThrownBy(() -> request.contract(NOW))
                .isInstanceOfSatisfying(InvalidStatusTransitionException.class, error -> {
                    assertThat(error.getFrom()).isEqualTo(ServiceRequestStatus.PUBLISHED);
                    assertThat(error.getTo()).isEqualTo(ServiceRequestStatus.CONTRACTED);
                });
    }

    // ---- Privacidad ----

    @Test
    void laDireccionExactaNoSeEntregaAntesDeLaContratacion() {
        ServiceRequest request = published();
        assertThat(request.isExactLocationVisible()).isFalse();
        assertThat(request.getExactLocation()).isEmpty();

        request.selectProvider(PROVIDER, NOW);
        assertThat(request.isExactLocationVisible()).isFalse();
        assertThat(request.getExactLocation()).isEmpty();
    }

    @Test
    void laDireccionExactaSeEntregaUnaVezContratada() {
        ServiceRequest request = published();
        request.selectProvider(PROVIDER, NOW);
        request.contract(NOW);

        assertThat(request.isExactLocationVisible()).isTrue();
        assertThat(request.getExactLocation()).hasValueSatisfying(location -> {
            assertThat(location.address()).isEqualTo("Calle 10 # 5-20, apto 301");
            assertThat(location.latitude()).isEqualTo(4.7110);
            assertThat(location.longitude()).isEqualTo(-74.0721);
        });
    }

    @Test
    void laDireccionExactaSigueDisponibleCuandoElServicioEstaEnCurso() {
        ServiceRequest request = published();
        request.selectProvider(PROVIDER, NOW);
        request.contract(NOW);
        request.start(NOW);

        assertThat(request.isExactLocationVisible()).isTrue();
        assertThat(request.getExactLocation()).hasValueSatisfying(location ->
                assertThat(location.address()).isEqualTo("Calle 10 # 5-20, apto 301"));
    }

    @Test
    void sinDireccionExactaNoHayNadaQueEntregarAunqueEsteContratada() {
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.PAINTING, "Pintar sala",
                new ApproximateLocation("Cali", "Granada"), null, TOMORROW,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW);
        request.selectProvider(PROVIDER, NOW);
        request.contract(NOW);

        assertThat(request.getExactLocation()).isEmpty();
    }

    @Test
    void laUbicacionAproximadaNuncaContieneLasCoordenadasExactas() {
        ServiceRequest request = published();

        assertThat(request.getApproximateLocation().latitude()).isNotEqualTo(EXACT.latitude());
        assertThat(request.getApproximateLocation().longitude()).isNotEqualTo(EXACT.longitude());
    }

    @Test
    void toStringNoExponeDescripcionNiDirecciones() {
        ServiceRequest request = published();
        request.selectProvider(PROVIDER, NOW);
        request.contract(NOW);

        assertThat(request.toString())
                .contains("PLUMBING", "CONTRACTED")
                .doesNotContain("Fuga", "Calle 10", "Chapinero", "Bogotá");
        assertThat(EXACT.toString()).doesNotContain("Calle 10");
    }

    @Test
    void dosSolicitudesSinIdentificadorNoSonIguales() {
        ServiceRequest first = published();
        ServiceRequest second = published();

        assertThat(first).isEqualTo(first).isNotEqualTo(second).isNotEqualTo("otra cosa");
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
