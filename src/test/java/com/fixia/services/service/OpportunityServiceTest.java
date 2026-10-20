package com.fixia.services.service;

import com.fixia.services.client.EligibilityCheckException;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Recorrido por lotes de las candidatas y filtro de elegibilidad (GC-337), con ms-users simulado. */
class OpportunityServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID PROVIDER = UUID.randomUUID();

    private final ServiceRequestRepository repository = mock(ServiceRequestRepository.class);
    private final TechnicianEligibilityClient eligibilityClient = mock(TechnicianEligibilityClient.class);
    private final OpportunityService service =
            new OpportunityService(repository, eligibilityClient, Clock.fixed(NOW, ZoneOffset.UTC));

    private static ServiceRequest request() {
        ServiceRequest request = ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, NOW.plusSeconds(3600), List.of(),
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        return request;
    }

    private static List<ServiceRequest> requests(int count) {
        return IntStream.range(0, count).mapToObj(i -> request()).toList();
    }

    /** Página {@code number} de tamaño {@code size} sobre {@code all}, con {@code hasNext} calculado como Spring Data. */
    private static Slice<ServiceRequest> slice(List<ServiceRequest> all, Pageable pageable) {
        int from = (int) pageable.getOffset();
        int to = Math.min(from + pageable.getPageSize(), all.size());
        List<ServiceRequest> content = from >= all.size() ? List.of() : all.subList(from, to);
        return new SliceImpl<>(content, pageable, to < all.size());
    }

    private void candidates(List<ServiceRequest> all) {
        when(repository.findOpenWithoutApplicationFrom(eq(PROVIDER), eq(NOW), any(Pageable.class)))
                .thenAnswer(call -> slice(all, call.getArgument(2)));
    }

    /** ms-users responde elegible solo para las solicitudes indicadas. */
    private void eligibleOnly(Set<UUID> eligibleIds) {
        when(eligibilityClient.checkAll(eq(PROVIDER), any())).thenAnswer(call -> {
            List<ServiceRequest> asked = call.getArgument(1);
            Map<UUID, TechnicianEligibilityClient.Result> results = new HashMap<>();
            for (ServiceRequest candidate : asked) {
                boolean eligible = eligibleIds.contains(candidate.getId());
                results.put(candidate.getId(), new TechnicianEligibilityClient.Result(candidate.getId(), eligible,
                        eligible ? List.of() : List.of("CATEGORY")));
            }
            return results;
        });
    }

    @Test
    void devuelveSoloLasSolicitudesQueMsUsersConsideraElegiblesEnElMismoOrden() {
        List<ServiceRequest> all = requests(5);
        candidates(all);
        eligibleOnly(Set.of(all.get(1).getId(), all.get(3).getId()));

        assertThat(service.findFor(PROVIDER)).containsExactly(all.get(1), all.get(3));
        verify(eligibilityClient, times(1)).checkAll(eq(PROVIDER), any());
    }

    @Test
    void sinCandidatasNoConsultaAMsUsers() {
        candidates(List.of());

        assertThat(service.findFor(PROVIDER)).isEmpty();

        verifyNoInteractions(eligibilityClient);
    }

    @Test
    void consultaLasCandidatasEnLotesDeCienYEncuentraLasDelSegundoLote() {
        List<ServiceRequest> all = requests(150);
        candidates(all);
        eligibleOnly(Set.of(all.get(120).getId()));

        assertThat(service.findFor(PROVIDER)).containsExactly(all.get(120));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ServiceRequest>> batches = ArgumentCaptor.forClass(List.class);
        verify(eligibilityClient, times(2)).checkAll(eq(PROVIDER), batches.capture());
        assertThat(batches.getAllValues().get(0)).hasSize(OpportunityService.BATCH_SIZE);
        assertThat(batches.getAllValues().get(1)).hasSize(50);
    }

    @Test
    void sePoneEnElLimiteYNoSigueConsultandoCuandoYaReunioLasOportunidadesQueDevuelve() {
        List<ServiceRequest> all = requests(300);
        candidates(all);
        eligibleOnly(all.stream().map(ServiceRequest::getId).collect(Collectors.toSet()));

        List<ServiceRequest> found = service.findFor(PROVIDER);

        assertThat(found).hasSize(OpportunityService.LIMIT).isEqualTo(all.subList(0, OpportunityService.LIMIT));
        verify(eligibilityClient, times(1)).checkAll(eq(PROVIDER), any());
    }

    @Test
    void noRevisaMasDeLasCandidatasDelTopeAunqueHayaMas() {
        List<ServiceRequest> all = requests(1000);
        candidates(all);
        eligibleOnly(Set.of());

        assertThat(service.findFor(PROVIDER)).isEmpty();

        verify(eligibilityClient, times(OpportunityService.MAX_SCAN / OpportunityService.BATCH_SIZE))
                .checkAll(eq(PROVIDER), any());
    }

    @Test
    void siMsUsersNoRespondeNoDevuelveNadaYPropagaElFalloCerrado() {
        candidates(requests(3));
        when(eligibilityClient.checkAll(eq(PROVIDER), any())).thenThrow(new EligibilityCheckException("caído"));

        assertThatThrownBy(() -> service.findFor(PROVIDER)).isInstanceOf(EligibilityCheckException.class);
    }

    @Test
    void usaElRelojDelServicioEnTodasLasConsultas() {
        candidates(requests(150));
        eligibleOnly(Set.of());

        service.findFor(PROVIDER);

        verify(repository, times(2)).findOpenWithoutApplicationFrom(eq(PROVIDER), eq(NOW), any(Pageable.class));
    }
}
