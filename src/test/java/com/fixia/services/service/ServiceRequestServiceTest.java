package com.fixia.services.service;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.dto.ApproximateLocationRequest;
import com.fixia.services.dto.CreateServiceRequestRequest;
import com.fixia.services.exception.InvalidServiceRequestException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.when;

/**
 * Reglas del servicio que la validación del cuerpo no alcanza a cubrir, con el reloj controlado.
 */
class ServiceRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final UUID CLIENT = UUID.randomUUID();

    private final ServiceRequestRepository repository = mock(ServiceRequestRepository.class);
    private final ServiceRequestService service =
            new ServiceRequestService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    private static CreateServiceRequestRequest request(Instant expectedAt) {
        return new CreateServiceRequestRequest(ServiceCategory.PLUMBING, "Fuga de agua",
                new ApproximateLocationRequest("Bogotá", "Chapinero", null, null), null, expectedAt, List.of());
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
                NOW.plusSeconds(3600), List.of(), NOW);
        when(repository.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.get(CLIENT, id)).isInstanceOf(ServiceRequestNotFoundException.class);
        assertThatThrownBy(() -> service.get(CLIENT, UUID.randomUUID()))
                .isInstanceOf(ServiceRequestNotFoundException.class);
    }
}
