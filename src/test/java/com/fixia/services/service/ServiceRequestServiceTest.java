package com.fixia.services.service;

import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.dto.ApproximateLocationRequest;
import com.fixia.services.dto.CreateServiceRequestRequest;
import com.fixia.services.exception.ApplicationNotFoundException;
import com.fixia.services.exception.InvalidServiceRequestException;
import com.fixia.services.exception.ServiceRequestNotAvailableException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas del servicio que la validación del cuerpo no alcanza a cubrir, con el reloj controlado.
 */
class ServiceRequestServiceTest {

    private static final BigDecimal PRICE = new BigDecimal("150000");

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final UUID CLIENT = UUID.randomUUID();

    private final ServiceRequestRepository repository = mock(ServiceRequestRepository.class);
    private final ServiceRequestApplicationRepository applicationRepository =
            mock(ServiceRequestApplicationRepository.class);
    private final BusinessEventRecorder events = mock(BusinessEventRecorder.class);
    private final ServiceRequestService service = new ServiceRequestService(repository, applicationRepository,
            events, Clock.fixed(NOW, ZoneOffset.UTC));

    private static CreateServiceRequestRequest request(Instant expectedAt) {
        return new CreateServiceRequestRequest(ServiceCategory.PLUMBING, "Fuga de agua",
                new ApproximateLocationRequest("Bogotá", "Chapinero", null, null), null, expectedAt,
                new BigDecimal("200000"), CurrencyCode.COP, List.of());
    }

    @Test
    void publicaConElRelojDelServicioYGuardaLaSolicitud() {
        when(repository.save(any(ServiceRequest.class))).thenAnswer(call -> call.getArgument(0));

        ServiceRequest created = service.create(CLIENT, request(NOW.plusSeconds(3600)));

        assertThat(created.getClientId()).isEqualTo(CLIENT);
        assertThat(created.getPublishedAt()).isEqualTo(NOW);
        verify(repository).save(created);
    }

    @Test
    void unMomentoEsperadoAnteriorAlRelojDelServicioSeRechazaSinGuardar() {
        assertThatThrownBy(() -> service.create(CLIENT, request(NOW.minusSeconds(1))))
                .isInstanceOf(InvalidServiceRequestException.class)
                .hasMessageContaining("momento esperado");

        verify(repository, never()).save(any());
    }

    @Test
    void consultarUnaSolicitudAjenaOInexistenteLanzaLaMismaExcepcion() {
        UUID id = UUID.randomUUID();
        ServiceRequest foreign = ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.PAINTING, "Pintar",
                new ApproximateLocation("Cali", "Granada"), null,
                NOW.plusSeconds(3600), new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW);
        when(repository.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.get(CLIENT, id)).isInstanceOf(ServiceRequestNotFoundException.class);
        assertThatThrownBy(() -> service.get(CLIENT, UUID.randomUUID()))
                .isInstanceOf(ServiceRequestNotFoundException.class);
    }

    @Test
    void losPostulantesSeConsultanSoloSiLaSolicitudEsDelCliente() {
        ServiceRequest own = persisted(CLIENT);
        ServiceRequestApplication application = ServiceRequestApplication.submit(own, UUID.randomUUID(),
                PRICE, CurrencyCode.COP, NOW);
        when(repository.findById(own.getId())).thenReturn(Optional.of(own));
        when(applicationRepository.findByServiceRequestIdOrderByCreatedAtAscIdAsc(own.getId()))
                .thenReturn(List.of(application));

        assertThat(service.listApplicants(CLIENT, own.getId())).containsExactly(application);
    }

    @Test
    void consultarLosPostulantesDeUnaSolicitudAjenaOInexistenteNoLlegaAlRepositorioDePostulaciones() {
        ServiceRequest foreign = persisted(UUID.randomUUID());
        when(repository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.listApplicants(CLIENT, foreign.getId()))
                .isInstanceOf(ServiceRequestNotFoundException.class);
        assertThatThrownBy(() -> service.listApplicants(CLIENT, UUID.randomUUID()))
                .isInstanceOf(ServiceRequestNotFoundException.class);

        verifyNoInteractions(applicationRepository);
    }

    @Test
    void seleccionarMarcaLaElegidaYRechazaLasDemasSobreLaSolicitudBloqueada() {
        ServiceRequest own = persisted(CLIENT);
        UUID chosenProvider = UUID.randomUUID();
        ServiceRequestApplication chosen = withId(ServiceRequestApplication.submit(own, chosenProvider,
                PRICE, CurrencyCode.COP, NOW));
        ServiceRequestApplication other = withId(ServiceRequestApplication.submit(own, UUID.randomUUID(),
                PRICE, CurrencyCode.COP, NOW));
        when(repository.findByIdAndClientIdForUpdate(own.getId(), CLIENT)).thenReturn(Optional.of(own));
        when(applicationRepository.findByServiceRequestIdOrderByCreatedAtAscIdAsc(own.getId()))
                .thenReturn(List.of(other, chosen));

        service.selectProvider(CLIENT, own.getId(), chosen.getId());

        assertThat(chosen.getStatus()).isEqualTo(ApplicationStatus.SELECTED);
        assertThat(other.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(own.getStatus()).isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
        assertThat(own.getSelectedProviderId()).isEqualTo(chosenProvider);
        assertThat(chosen.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void seleccionarEnUnaSolicitudAjenaOInexistenteNoTocaLasPostulaciones() {
        ServiceRequest foreign = persisted(UUID.randomUUID());
        when(repository.findByIdAndClientIdForUpdate(foreign.getId(), CLIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.selectProvider(CLIENT, foreign.getId(), UUID.randomUUID()))
                .isInstanceOf(ServiceRequestNotFoundException.class);
        assertThatThrownBy(() -> service.selectProvider(CLIENT, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ServiceRequestNotFoundException.class);

        verifyNoInteractions(applicationRepository);
        verify(repository, never()).findByIdForUpdate(any());
        assertThat(foreign.getStatus()).isEqualTo(ServiceRequestStatus.PUBLISHED);
    }

    @Test
    void repetirLaSeleccionYaHechaDevuelveElEstadoActualSinCambiarNada() {
        ServiceRequest own = persisted(CLIENT);
        ServiceRequestApplication chosen = withId(ServiceRequestApplication.submit(own, UUID.randomUUID(),
                PRICE, CurrencyCode.COP, NOW));
        chosen.select(NOW);
        own.selectProvider(chosen.getProviderId(), NOW);
        ReflectionTestUtils.setField(chosen, "serviceRequestId", own.getId());
        when(repository.findByIdAndClientIdForUpdate(own.getId(), CLIENT)).thenReturn(Optional.of(own));
        when(applicationRepository.findById(chosen.getId())).thenReturn(Optional.of(chosen));

        assertThat(service.selectProvider(CLIENT, own.getId(), chosen.getId())).isSameAs(own);

        assertThat(own.getStatus()).isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
        assertThat(chosen.getStatus()).isEqualTo(ApplicationStatus.SELECTED);
    }

    @Test
    void seleccionarOtraPostulacionCuandoYaHayPrestadorLanzaNoDisponible() {
        ServiceRequest own = persisted(CLIENT);
        ServiceRequestApplication chosen = withId(ServiceRequestApplication.submit(own, UUID.randomUUID(),
                PRICE, CurrencyCode.COP, NOW));
        ServiceRequestApplication rejected = withId(ServiceRequestApplication.submit(own, UUID.randomUUID(),
                PRICE, CurrencyCode.COP, NOW));
        ReflectionTestUtils.setField(chosen, "serviceRequestId", own.getId());
        ReflectionTestUtils.setField(rejected, "serviceRequestId", own.getId());
        chosen.select(NOW);
        rejected.reject(NOW);
        own.selectProvider(chosen.getProviderId(), NOW);
        when(repository.findByIdAndClientIdForUpdate(own.getId(), CLIENT)).thenReturn(Optional.of(own));
        when(applicationRepository.findById(rejected.getId())).thenReturn(Optional.of(rejected));
        when(applicationRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(applicationRepository.findById(rejected.getId())).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> service.selectProvider(CLIENT, own.getId(), rejected.getId()))
                .isInstanceOf(ServiceRequestNotAvailableException.class);
        assertThatThrownBy(() -> service.selectProvider(CLIENT, own.getId(), UUID.randomUUID()))
                .isInstanceOf(ServiceRequestNotAvailableException.class);
    }

    @Test
    void seleccionarUnaPostulacionQueNoEsDeLaSolicitudLanzaNotFoundSinCambiarNada() {
        ServiceRequest own = persisted(CLIENT);
        ServiceRequestApplication mine = withId(ServiceRequestApplication.submit(own, UUID.randomUUID(),
                PRICE, CurrencyCode.COP, NOW));
        when(repository.findByIdAndClientIdForUpdate(own.getId(), CLIENT)).thenReturn(Optional.of(own));
        when(applicationRepository.findByServiceRequestIdOrderByCreatedAtAscIdAsc(own.getId()))
                .thenReturn(List.of(mine));

        assertThatThrownBy(() -> service.selectProvider(CLIENT, own.getId(), UUID.randomUUID()))
                .isInstanceOf(ApplicationNotFoundException.class);

        assertThat(mine.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(own.getStatus()).isEqualTo(ServiceRequestStatus.PUBLISHED);
    }

    @Test
    void seleccionarConElMomentoEsperadoYaPasadoOLaSolicitudYaCerradaLanzaNoDisponible() {
        ServiceRequest expired = persisted(CLIENT, NOW.minusSeconds(1));
        ServiceRequest selected = persisted(CLIENT);
        selected.selectProvider(UUID.randomUUID(), NOW);
        when(repository.findByIdAndClientIdForUpdate(expired.getId(), CLIENT)).thenReturn(Optional.of(expired));
        when(repository.findByIdAndClientIdForUpdate(selected.getId(), CLIENT)).thenReturn(Optional.of(selected));

        assertThatThrownBy(() -> service.selectProvider(CLIENT, expired.getId(), UUID.randomUUID()))
                .isInstanceOf(ServiceRequestNotAvailableException.class)
                .hasMessage("La solicitud ya no admite seleccionar prestador");
        assertThatThrownBy(() -> service.selectProvider(CLIENT, selected.getId(), UUID.randomUUID()))
                .isInstanceOf(ServiceRequestNotAvailableException.class);

        verify(applicationRepository, never()).findByServiceRequestIdOrderByCreatedAtAscIdAsc(any());
    }

    private static ServiceRequestApplication withId(ServiceRequestApplication application) {
        ReflectionTestUtils.setField(application, "id", UUID.randomUUID());
        return application;
    }

    /** Una solicitud con id, como la entrega el repositorio (el id lo genera JPA al guardar). */
    private static ServiceRequest persisted(UUID clientId) {
        return persisted(clientId, NOW.plusSeconds(3600));
    }

    private static ServiceRequest persisted(UUID clientId, Instant expectedAt) {
        ServiceRequest request = ServiceRequest.publish(clientId, ServiceCategory.PAINTING, "Pintar",
                new ApproximateLocation("Cali", "Granada"), null, expectedAt,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), NOW.minusSeconds(7200));
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        return request;
    }
}
