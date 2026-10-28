package com.fixia.services.domain;

import com.fixia.services.exception.InvalidApplicationTransitionException;
import com.fixia.services.exception.ServiceRequestNotAvailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceRequestApplicationTest {

    private static final BigDecimal PRICE = new BigDecimal("150000");

    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");
    private static final UUID PROVIDER = UUID.randomUUID();

    /** Solicitud publicada para dentro de dos días; el id lo asignaría la base al guardarla. */
    private static ServiceRequest publishedRequest() {
        ServiceRequest request = ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero"), null,
                NOW.plus(Duration.ofDays(2)),
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        return request;
    }

    @Test
    void elTecnicoSePostulaAUnaSolicitudPublicada() {
        ServiceRequest request = publishedRequest();

        ServiceRequestApplication application = ServiceRequestApplication.submit(request, PROVIDER,
                PRICE, CurrencyCode.COP, NOW);

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(application.getServiceRequestId()).isEqualTo(request.getId());
        assertThat(application.getProviderId()).isEqualTo(PROVIDER);
        assertThat(application.getCreatedAt()).isEqualTo(NOW);
        assertThat(application.getUpdatedAt()).isEqualTo(NOW);
        assertThat(application.getProposedAmount()).isEqualByComparingTo("150000");
        assertThat(application.getProposedAmount().scale()).isEqualTo(2);
        assertThat(application.getCurrency()).isEqualTo(CurrencyCode.COP);
    }

    @Test
    void elPrecioPropuestoEsObligatorioMayorQueCeroYConMaximoDosDecimales() {
        ServiceRequest request = publishedRequest();

        assertThatThrownBy(() -> ServiceRequestApplication.submit(request, PROVIDER, null, CurrencyCode.COP, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("El precio propuesto es obligatorio");
        assertThatThrownBy(() -> ServiceRequestApplication.submit(request, PROVIDER, BigDecimal.ZERO,
                CurrencyCode.COP, NOW)).hasMessage("El precio propuesto debe ser mayor que cero");
        assertThatThrownBy(() -> ServiceRequestApplication.submit(request, PROVIDER, new BigDecimal("-1"),
                CurrencyCode.COP, NOW)).hasMessage("El precio propuesto debe ser mayor que cero");
        assertThatThrownBy(() -> ServiceRequestApplication.submit(request, PROVIDER, new BigDecimal("100.123"),
                CurrencyCode.COP, NOW)).hasMessage("El precio propuesto admite máximo dos decimales");
        assertThatThrownBy(() -> ServiceRequestApplication.submit(request, PROVIDER,
                Quotation.MAX_AMOUNT.add(BigDecimal.ONE), CurrencyCode.COP, NOW))
                .hasMessageStartingWith("El precio propuesto no puede superar");
        assertThatThrownBy(() -> ServiceRequestApplication.submit(request, PROVIDER, PRICE, null, NOW))
                .hasMessage("La moneda es obligatoria");
    }

    @Test
    void losCerosALaDerechaDelDecimalNoCuentanComoDecimales() {
        ServiceRequestApplication application = ServiceRequestApplication.submit(publishedRequest(), PROVIDER,
                new BigDecimal("150000.500"), CurrencyCode.COP, NOW);

        assertThat(application.getProposedAmount()).isEqualByComparingTo("150000.50");
    }

    @Test
    void noSePuedePostularAUnaSolicitudQueYaNoEstaPublicada() {
        ServiceRequest selected = publishedRequest();
        selected.selectProvider(UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> ServiceRequestApplication.submit(selected, PROVIDER, PRICE, CurrencyCode.COP, NOW))
                .isInstanceOf(ServiceRequestNotAvailableException.class)
                .hasMessage("La solicitud ya no recibe postulaciones");

        ServiceRequest cancelled = publishedRequest();
        cancelled.cancel(NOW);
        assertThatThrownBy(() -> ServiceRequestApplication.submit(cancelled, PROVIDER, PRICE, CurrencyCode.COP, NOW))
                .isInstanceOf(ServiceRequestNotAvailableException.class);
    }

    @Test
    void noSePuedePostularCuandoElMomentoEsperadoYaPaso() {
        ServiceRequest request = publishedRequest();

        assertThatThrownBy(() -> ServiceRequestApplication.submit(request, PROVIDER,
                PRICE, CurrencyCode.COP, request.getExpectedAt()))
                .isInstanceOf(ServiceRequestNotAvailableException.class);
        assertThatThrownBy(() -> ServiceRequestApplication.requireOpen(request,
                request.getExpectedAt().plusSeconds(1))).isInstanceOf(ServiceRequestNotAvailableException.class);
        ServiceRequestApplication.requireOpen(request, request.getExpectedAt().minusSeconds(1));
    }

    @Test
    void laSolicitudYElTecnicoSonObligatorios() {
        assertThatThrownBy(() -> ServiceRequestApplication.submit(null, PROVIDER, PRICE, CurrencyCode.COP, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceRequestApplication.submit(publishedRequest(), null,
                PRICE, CurrencyCode.COP, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unaEmpresaSePostulaConSuTecnicoComoEjecutor() {
        UUID company = UUID.randomUUID();
        UUID technician = UUID.randomUUID();

        ServiceRequestApplication application = ServiceRequestApplication.submitForCompany(publishedRequest(),
                company, technician, PRICE, CurrencyCode.COP, NOW);

        assertThat(application.getProviderId()).isEqualTo(company);
        assertThat(application.getProviderType()).isEqualTo(ProviderType.COMPANY);
        assertThat(application.getTechnicianId()).isEqualTo(technician);
        assertThat(application.getExecutorId()).isEqualTo(technician);
        assertThat(application.getProposedAmount()).isEqualByComparingTo("150000");
    }

    @Test
    void unIndependienteEsSuPropioEjecutor() {
        ServiceRequestApplication application = ServiceRequestApplication.submit(publishedRequest(), PROVIDER,
                PRICE, CurrencyCode.COP, NOW);

        assertThat(application.getProviderType()).isEqualTo(ProviderType.INDEPENDENT);
        assertThat(application.getTechnicianId()).isNull();
        assertThat(application.getExecutorId()).isEqualTo(PROVIDER);
    }

    @Test
    void laEmpresaDebeProponerUnTecnicoDistintoDeEllaMisma() {
        UUID company = UUID.randomUUID();

        assertThatThrownBy(() -> ServiceRequestApplication.submitForCompany(publishedRequest(), null,
                UUID.randomUUID(), PRICE, CurrencyCode.COP, NOW)).hasMessage("La empresa es obligatoria");
        assertThatThrownBy(() -> ServiceRequestApplication.submitForCompany(publishedRequest(), company, null,
                PRICE, CurrencyCode.COP, NOW)).hasMessage("El técnico propuesto es obligatorio");
        assertThatThrownBy(() -> ServiceRequestApplication.submitForCompany(publishedRequest(), company, company,
                PRICE, CurrencyCode.COP, NOW)).hasMessage("La empresa no puede proponerse a sí misma como técnico");
        assertThatThrownBy(() -> ServiceRequestApplication.submitForCompany(publishedRequest(), company,
                UUID.randomUUID(), BigDecimal.ZERO, CurrencyCode.COP, NOW))
                .hasMessage("El precio propuesto debe ser mayor que cero");
    }

    @Test
    void laSeleccionPasaLaPostulacionASeleccionadaORechazadaUnaSolaVez() {
        ServiceRequestApplication selected = ServiceRequestApplication.submit(publishedRequest(), PROVIDER,
                PRICE, CurrencyCode.COP, NOW);
        selected.select(NOW.plusSeconds(60));
        assertThat(selected.getStatus()).isEqualTo(ApplicationStatus.SELECTED);
        assertThat(selected.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));

        ServiceRequestApplication rejected = ServiceRequestApplication.submit(publishedRequest(), PROVIDER,
                PRICE, CurrencyCode.COP, NOW);
        rejected.reject(NOW.plusSeconds(60));
        assertThat(rejected.getStatus()).isEqualTo(ApplicationStatus.REJECTED);

        InvalidApplicationTransitionException error = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidApplicationTransitionException.class, () -> rejected.select(NOW.plusSeconds(120)));
        assertThat(error.getFrom()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(error.getTo()).isEqualTo(ApplicationStatus.SELECTED);
        assertThatThrownBy(() -> selected.reject(NOW.plusSeconds(120)))
                .isInstanceOf(InvalidApplicationTransitionException.class);
    }

    @Test
    void elTextoDeDepuracionNoTraeDatosDelTecnicoYLaIgualdadEsPorId() {
        ServiceRequestApplication application = ServiceRequestApplication.submit(publishedRequest(), PROVIDER,
                PRICE, CurrencyCode.COP, NOW);
        ServiceRequestApplication other = ServiceRequestApplication.submit(publishedRequest(), PROVIDER,
                PRICE, CurrencyCode.COP, NOW);

        assertThat(application.toString()).contains("APPLIED").doesNotContain(PROVIDER.toString());
        assertThat(application).isEqualTo(application).isNotEqualTo(other).isNotEqualTo("otra cosa");
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(application, "id", id);
        ReflectionTestUtils.setField(other, "id", id);
        assertThat(application).isEqualTo(other).hasSameHashCodeAs(other);
    }
}
