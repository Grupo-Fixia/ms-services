package com.fixia.services.domain;

import com.fixia.services.exception.InvalidApplicationTransitionException;
import com.fixia.services.exception.ServiceRequestNotAvailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceRequestApplicationTest {

    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");
    private static final UUID PROVIDER = UUID.randomUUID();

    /** Solicitud publicada para dentro de dos días; el id lo asignaría la base al guardarla. */
    private static ServiceRequest publishedRequest() {
        ServiceRequest request = ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero"), null,
                NOW.plus(Duration.ofDays(2)), List.of(), NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        return request;
    }

    @Test
    void elTecnicoSePostulaAUnaSolicitudPublicada() {
        ServiceRequest request = publishedRequest();

        ServiceRequestApplication application = ServiceRequestApplication.submit(request, PROVIDER, NOW);

        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(application.getServiceRequestId()).isEqualTo(request.getId());
        assertThat(application.getProviderId()).isEqualTo(PROVIDER);
        assertThat(application.getCreatedAt()).isEqualTo(NOW);
        assertThat(application.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void noSePuedePostularAUnaSolicitudQueYaNoEstaPublicada() {
        ServiceRequest selected = publishedRequest();
        selected.selectProvider(UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> ServiceRequestApplication.submit(selected, PROVIDER, NOW))
                .isInstanceOf(ServiceRequestNotAvailableException.class)
                .hasMessage("La solicitud ya no recibe postulaciones");

        ServiceRequest cancelled = publishedRequest();
        cancelled.cancel(NOW);
        assertThatThrownBy(() -> ServiceRequestApplication.submit(cancelled, PROVIDER, NOW))
                .isInstanceOf(ServiceRequestNotAvailableException.class);
    }

    @Test
    void noSePuedePostularCuandoElMomentoEsperadoYaPaso() {
        ServiceRequest request = publishedRequest();

        assertThatThrownBy(() -> ServiceRequestApplication.submit(request, PROVIDER, request.getExpectedAt()))
                .isInstanceOf(ServiceRequestNotAvailableException.class);
        assertThatThrownBy(() -> ServiceRequestApplication.requireOpen(request,
                request.getExpectedAt().plusSeconds(1))).isInstanceOf(ServiceRequestNotAvailableException.class);
        ServiceRequestApplication.requireOpen(request, request.getExpectedAt().minusSeconds(1));
    }

    @Test
    void laSolicitudYElTecnicoSonObligatorios() {
        assertThatThrownBy(() -> ServiceRequestApplication.submit(null, PROVIDER, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceRequestApplication.submit(publishedRequest(), null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laSeleccionPasaLaPostulacionASeleccionadaORechazadaUnaSolaVez() {
        ServiceRequestApplication selected = ServiceRequestApplication.submit(publishedRequest(), PROVIDER, NOW);
        selected.select(NOW.plusSeconds(60));
        assertThat(selected.getStatus()).isEqualTo(ApplicationStatus.SELECTED);
        assertThat(selected.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));

        ServiceRequestApplication rejected = ServiceRequestApplication.submit(publishedRequest(), PROVIDER, NOW);
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
        ServiceRequestApplication application = ServiceRequestApplication.submit(publishedRequest(), PROVIDER, NOW);
        ServiceRequestApplication other = ServiceRequestApplication.submit(publishedRequest(), PROVIDER, NOW);

        assertThat(application.toString()).contains("APPLIED").doesNotContain(PROVIDER.toString());
        assertThat(application).isEqualTo(application).isNotEqualTo(other).isNotEqualTo("otra cosa");
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(application, "id", id);
        ReflectionTestUtils.setField(other, "id", id);
        assertThat(application).isEqualTo(other).hasSameHashCodeAs(other);
    }
}
