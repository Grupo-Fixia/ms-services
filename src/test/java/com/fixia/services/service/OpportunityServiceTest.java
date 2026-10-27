package com.fixia.services.service;

import com.fixia.services.client.EligibilityCheckException;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/**
 * Recorrido por lotes y por cursor de las candidatas, con ms-users simulado. La lista que "devuelve" el repositorio
 * es un {@code List} ordenado como en la base (más reciente primero) y el doble aplica el cursor sobre él.
 */
class OpportunityServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final UUID PROVIDER = UUID.randomUUID();

    private final ServiceRequestRepository repository = mock(ServiceRequestRepository.class);
    private final TechnicianEligibilityClient eligibilityClient = mock(TechnicianEligibilityClient.class);
    private final OpportunityService service =
            new OpportunityService(repository, eligibilityClient, Clock.fixed(NOW, ZoneOffset.UTC));

    /** Solicitud número {@code index}: más pequeño = más reciente. */
    private static ServiceRequest request(int index) {
        ServiceRequest request = ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, NOW.plusSeconds(3600),
                new BigDecimal("200000"), CurrencyCode.COP, List.of(),
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(request, "publishedAt", NOW.minusSeconds(60 + index));
        return request;
    }

    private static List<ServiceRequest> requests(int count) {
        return IntStream.range(0, count).mapToObj(OpportunityServiceTest::request).toList();
    }

    private static Slice<ServiceRequest> slice(List<ServiceRequest> remaining, Pageable pageable) {
        int to = Math.min(pageable.getPageSize(), remaining.size());
        return new SliceImpl<>(remaining.subList(0, to), pageable, to < remaining.size());
    }

    private void candidates(List<ServiceRequest> all) {
        when(repository.findOpenWithoutApplicationFrom(eq(PROVIDER), eq(NOW), any(Pageable.class)))
                .thenAnswer(call -> slice(all, call.getArgument(2)));
        when(repository.findOpenWithoutApplicationAfter(eq(PROVIDER), eq(NOW), any(), any(), any(Pageable.class)))
                .thenAnswer(call -> {
                    Instant at = call.getArgument(2);
                    UUID id = call.getArgument(3);
                    int position = IntStream.range(0, all.size())
                            .filter(i -> all.get(i).getPublishedAt().equals(at) && all.get(i).getId().equals(id))
                            .findFirst().orElseThrow();
                    return slice(all.subList(position + 1, all.size()), call.getArgument(4));
                });
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

    private static Set<UUID> ids(List<ServiceRequest> all, int... indexes) {
        return IntStream.of(indexes).mapToObj(i -> all.get(i).getId()).collect(Collectors.toSet());
    }

    /** Recorre todas las páginas siguiendo el cursor y devuelve lo que entregó cada una. */
    private List<OpportunityService.Page> walk(int limit) {
        List<OpportunityService.Page> pages = new ArrayList<>();
        OpportunityCursor cursor = null;
        do {
            OpportunityService.Page page = service.findPage(PROVIDER, limit, cursor);
            pages.add(page);
            cursor = page.next();
        } while (cursor != null && pages.size() < 1000);
        return pages;
    }

    @Test
    void devuelveSoloLasElegiblesEnElMismoOrdenYSinCursorSiNoQuedanCandidatas() {
        List<ServiceRequest> all = requests(5);
        candidates(all);
        eligibleOnly(ids(all, 1, 3));

        OpportunityService.Page page = service.findPage(PROVIDER, 20, null);

        assertThat(page.items()).containsExactly(all.get(1), all.get(3));
        assertThat(page.next()).isNull();
        verify(eligibilityClient, times(1)).checkAll(eq(PROVIDER), any());
    }

    @Test
    void sinCandidatasNoConsultaAMsUsersNiDevuelveCursor() {
        candidates(List.of());

        OpportunityService.Page page = service.findPage(PROVIDER, 20, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.next()).isNull();
        verifyNoInteractions(eligibilityClient);
    }

    @Test
    void alLlegarAlLimiteDevuelveElCursorDeLaUltimaDevuelta() {
        List<ServiceRequest> all = requests(10);
        candidates(all);
        eligibleOnly(ids(all, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9));

        OpportunityService.Page page = service.findPage(PROVIDER, 3, null);

        assertThat(page.items()).containsExactly(all.get(0), all.get(1), all.get(2));
        assertThat(page.next()).isEqualTo(OpportunityCursor.of(all.get(2)));
    }

    @Test
    void siLaUltimaDevueltaEsLaUltimaCandidataNoHayCursor() {
        List<ServiceRequest> all = requests(3);
        candidates(all);
        eligibleOnly(ids(all, 0, 1, 2));

        OpportunityService.Page page = service.findPage(PROVIDER, 3, null);

        assertThat(page.items()).hasSize(3);
        assertThat(page.next()).isNull();
    }

    @Test
    void continuaDespuesDelCursorSinRepetirLoYaEntregado() {
        List<ServiceRequest> all = requests(10);
        candidates(all);
        eligibleOnly(ids(all, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9));

        OpportunityService.Page second = service.findPage(PROVIDER, 3, OpportunityCursor.of(all.get(2)));

        assertThat(second.items()).containsExactly(all.get(3), all.get(4), all.get(5));
        assertThat(second.next()).isEqualTo(OpportunityCursor.of(all.get(5)));
    }

    @Test
    void consultaLasCandidatasEnLotesDeCienYEncuentraLasDelSegundoLote() {
        List<ServiceRequest> all = requests(150);
        candidates(all);
        eligibleOnly(ids(all, 120));

        OpportunityService.Page page = service.findPage(PROVIDER, 20, null);

        assertThat(page.items()).containsExactly(all.get(120));
        assertThat(page.next()).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ServiceRequest>> batches = ArgumentCaptor.forClass(List.class);
        verify(eligibilityClient, times(2)).checkAll(eq(PROVIDER), batches.capture());
        assertThat(batches.getAllValues().get(0)).hasSize(OpportunityService.BATCH_SIZE);
        assertThat(batches.getAllValues().get(1)).hasSize(50);
    }

    @Test
    void siSeAgotaElPresupuestoDeRevisionDevuelveLoEncontradoYUnCursorParaSeguir() {
        List<ServiceRequest> all = requests(1000);
        candidates(all);
        eligibleOnly(Set.of());

        OpportunityService.Page page = service.findPage(PROVIDER, 20, null);

        assertThat(page.items()).isEmpty();
        assertThat(page.next()).isEqualTo(OpportunityCursor.of(all.get(OpportunityService.MAX_SCAN - 1)));
        verify(eligibilityClient, times(OpportunityService.MAX_SCAN / OpportunityService.BATCH_SIZE))
                .checkAll(eq(PROVIDER), any());
    }

    @Test
    void recorrerTodasLasPaginasEntregaExactamenteLasElegiblesSinHuecosNiRepetidos() {
        List<ServiceRequest> all = requests(1300);
        candidates(all);
        // Elegibles escasas y repartidas: unas al principio, otras más allá del primer presupuesto de revisión.
        int[] eligibleIndexes = {0, 7, 499, 500, 501, 777, 1100, 1299};
        eligibleOnly(ids(all, eligibleIndexes));

        for (int limit : new int[]{1, 3, 50}) {
            List<OpportunityService.Page> pages = walk(limit);

            List<ServiceRequest> delivered = pages.stream().flatMap(p -> p.items().stream()).toList();
            assertThat(delivered).as("límite %d", limit)
                    .containsExactlyElementsOf(IntStream.of(eligibleIndexes).mapToObj(all::get).toList());
            assertThat(pages.get(pages.size() - 1).next()).isNull();
            assertThat(pages.subList(0, pages.size() - 1)).allSatisfy(page -> assertThat(page.next()).isNotNull());
        }
    }

    @Test
    void unaPaginaPuedeVenirVaciaConCursorCuandoLasCompatiblesEstanMasAtras() {
        List<ServiceRequest> all = requests(700);
        candidates(all);
        eligibleOnly(ids(all, 650));

        OpportunityService.Page first = service.findPage(PROVIDER, 20, null);
        OpportunityService.Page second = service.findPage(PROVIDER, 20, first.next());

        assertThat(first.items()).isEmpty();
        assertThat(first.next()).isNotNull();
        assertThat(second.items()).containsExactly(all.get(650));
        assertThat(second.next()).isNull();
    }

    @Test
    void unResultadoAusenteSeTrataComoNoElegible() {
        List<ServiceRequest> all = requests(2);
        candidates(all);
        when(eligibilityClient.checkAll(eq(PROVIDER), any())).thenReturn(Map.of(
                all.get(0).getId(), new TechnicianEligibilityClient.Result(all.get(0).getId(), true, List.of())));

        assertThat(service.findPage(PROVIDER, 20, null).items()).containsExactly(all.get(0));
    }

    @Test
    void siMsUsersNoRespondeNoDevuelveNadaYPropagaElFalloCerrado() {
        candidates(requests(3));
        when(eligibilityClient.checkAll(eq(PROVIDER), any())).thenThrow(new EligibilityCheckException("caído"));

        assertThatThrownBy(() -> service.findPage(PROVIDER, 20, null)).isInstanceOf(EligibilityCheckException.class);
    }

    @Test
    void usaElRelojDelServicioEnTodasLasConsultas() {
        candidates(requests(150));
        eligibleOnly(Set.of());

        service.findPage(PROVIDER, 20, null);

        verify(repository, times(1)).findOpenWithoutApplicationFrom(eq(PROVIDER), eq(NOW), any(Pageable.class));
        verify(repository, times(1)).findOpenWithoutApplicationAfter(eq(PROVIDER), eq(NOW), any(), any(),
                any(Pageable.class));
    }
}
