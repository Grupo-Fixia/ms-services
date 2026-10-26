package com.fixia.services.controller;

import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.client.TechnicianProfileClient;
import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Selección única de prestador por el cliente dueño de la solicitud (GC-348). La autenticación se simula con el
 * token ya validado ({@code jwt()}); la validación real del token se prueba en {@code RevocationPipelineTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SelectProviderControllerTest {

    private static final BigDecimal PRICE = new BigDecimal("150000");

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final UUID THIRD = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TechnicianEligibilityClient eligibilityClient;

    @MockBean
    private TechnicianProfileClient profileClient;

    @BeforeEach
    void cleanDatabase() {
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
        when(eligibilityClient.check(any(), any()))
                .thenReturn(new TechnicianEligibilityClient.Result(UUID.randomUUID(), true, List.of()));
    }

    // ---------- Utilidades ----------

    private ServiceRequest request(UUID clientId) {
        Instant now = Instant.now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(clientId, ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero", 4.6533, -74.0836),
                new ExactLocation("Calle 10 # 5-20, apto 301"), now.plus(Duration.ofDays(2)), List.of(), now));
    }

    private ServiceRequestApplication applied(ServiceRequest request, UUID provider, Instant at) {
        return applicationRepository.saveAndFlush(ServiceRequestApplication.submit(request, provider,
                PRICE, CurrencyCode.COP, at));
    }

    /** Solicitud con los tres postulantes de siempre, en orden FIRST, SECOND, THIRD. */
    private Map<UUID, ServiceRequestApplication> requestWithApplicants() {
        ServiceRequest request = request(CLIENT);
        Instant base = Instant.now();
        applied(request, FIRST, base);
        applied(request, SECOND, base.plusSeconds(10));
        applied(request, THIRD, base.plusSeconds(20));
        return applicationsByProvider(request.getId());
    }

    private Map<UUID, ServiceRequestApplication> applicationsByProvider(UUID requestId) {
        return applicationRepository.findByServiceRequestIdOrderByCreatedAtAscIdAsc(requestId).stream()
                .collect(Collectors.toMap(ServiceRequestApplication::getProviderId, a -> a));
    }

    private static String selection(UUID requestId, UUID applicationId) {
        return "/api/services/requests/" + requestId + "/applications/" + applicationId + "/selection";
    }

    private ResultActions select(UUID client, UUID requestId, UUID applicationId) throws Exception {
        return mockMvc.perform(post(selection(requestId, applicationId)).with(client(client)));
    }

    private static RequestPostProcessor client(UUID userId) {
        return role(userId, "ROLE_CLIENT");
    }

    private static RequestPostProcessor role(UUID userId, String role) {
        return jwt().jwt(token -> token.subject("usr_" + userId)).authorities(new SimpleGrantedAuthority(role));
    }

    private Map<UUID, ApplicationStatus> statuses(UUID requestId) {
        return applicationsByProvider(requestId).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getStatus()));
    }

    // ---------- Seleccionar ----------

    @Test
    void elClienteSeleccionaUnPrestadorYLasDemasPostulacionesQuedanRechazadas() throws Exception {
        Map<UUID, ServiceRequestApplication> applications = requestWithApplicants();
        UUID requestId = applications.get(FIRST).getServiceRequestId();

        select(CLIENT, requestId, applications.get(SECOND).getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("PROVIDER_SELECTED"))
                .andExpect(jsonPath("$.selectedProviderId").value(SECOND.toString()))
                .andExpect(jsonPath("$.exactLocation").doesNotExist());

        assertThat(statuses(requestId)).containsEntry(FIRST, ApplicationStatus.REJECTED)
                .containsEntry(SECOND, ApplicationStatus.SELECTED)
                .containsEntry(THIRD, ApplicationStatus.REJECTED);
        ServiceRequest saved = serviceRequestRepository.findById(requestId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
        assertThat(saved.getSelectedProviderId()).isEqualTo(SECOND);
    }

    @Test
    void laDecisionQuedaVisibleEnLaConsultaDePostulantes() throws Exception {
        Map<UUID, ServiceRequestApplication> applications = requestWithApplicants();
        UUID requestId = applications.get(FIRST).getServiceRequestId();

        select(CLIENT, requestId, applications.get(THIRD).getId()).andExpect(status().isOk());

        mockMvc.perform(get("/api/services/requests/" + requestId + "/applications").with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status", contains("REJECTED", "REJECTED", "SELECTED")));
    }

    @Test
    void unaUnicaPostulacionTambienSePuedeSeleccionar() throws Exception {
        ServiceRequest request = request(CLIENT);
        ServiceRequestApplication only = applied(request, FIRST, Instant.now());

        select(CLIENT, request.getId(), only.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedProviderId").value(FIRST.toString()));
    }

    @Test
    void lasPostulacionesDeOtraSolicitudNoCambian() throws Exception {
        Map<UUID, ServiceRequestApplication> applications = requestWithApplicants();
        UUID requestId = applications.get(FIRST).getServiceRequestId();
        ServiceRequest another = request(CLIENT);
        applied(another, FIRST, Instant.now());

        select(CLIENT, requestId, applications.get(FIRST).getId()).andExpect(status().isOk());

        assertThat(statuses(another.getId())).containsOnly(Map.entry(FIRST, ApplicationStatus.APPLIED));
        assertThat(serviceRequestRepository.findById(another.getId()).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.PUBLISHED);
    }

    // ---------- Un solo prestador ----------

    @Test
    void unaSegundaSeleccionResponde409YNoCambiaLaDecision() throws Exception {
        Map<UUID, ServiceRequestApplication> applications = requestWithApplicants();
        UUID requestId = applications.get(FIRST).getServiceRequestId();
        select(CLIENT, requestId, applications.get(FIRST).getId()).andExpect(status().isOk());

        select(CLIENT, requestId, applications.get(SECOND).getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Solicitud no disponible"))
                .andExpect(jsonPath("$.detail").value("La solicitud ya no admite seleccionar prestador"));

        assertThat(statuses(requestId)).containsEntry(FIRST, ApplicationStatus.SELECTED)
                .containsEntry(SECOND, ApplicationStatus.REJECTED);
        assertThat(serviceRequestRepository.findById(requestId).orElseThrow().getSelectedProviderId())
                .isEqualTo(FIRST);
    }

    @Test
    void repetirLaMismaSeleccionEsIdempotenteYDevuelveElEstadoActual() throws Exception {
        Map<UUID, ServiceRequestApplication> applications = requestWithApplicants();
        UUID requestId = applications.get(FIRST).getServiceRequestId();
        select(CLIENT, requestId, applications.get(SECOND).getId()).andExpect(status().isOk());
        Instant decidedAt = serviceRequestRepository.findById(requestId).orElseThrow().getUpdatedAt();

        select(CLIENT, requestId, applications.get(SECOND).getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROVIDER_SELECTED"))
                .andExpect(jsonPath("$.selectedProviderId").value(SECOND.toString()));

        assertThat(serviceRequestRepository.findById(requestId).orElseThrow().getUpdatedAt()).isEqualTo(decidedAt);
        assertThat(statuses(requestId)).containsEntry(FIRST, ApplicationStatus.REJECTED)
                .containsEntry(SECOND, ApplicationStatus.SELECTED)
                .containsEntry(THIRD, ApplicationStatus.REJECTED);
    }

    @Test
    void elReintentoSoloEsIdempotenteParaLaPostulacionElegida() throws Exception {
        Map<UUID, ServiceRequestApplication> applications = requestWithApplicants();
        UUID requestId = applications.get(FIRST).getServiceRequestId();
        ServiceRequest another = request(CLIENT);
        ServiceRequestApplication foreign = applied(another, SECOND, Instant.now());
        select(CLIENT, requestId, applications.get(FIRST).getId()).andExpect(status().isOk());

        select(CLIENT, requestId, applications.get(THIRD).getId()).andExpect(status().isConflict());
        select(CLIENT, requestId, foreign.getId()).andExpect(status().isConflict());
        select(CLIENT, requestId, UUID.randomUUID()).andExpect(status().isConflict());
    }

    @Test
    void unaSolicitudCanceladaOConLaFechaVencidaNoPermiteSeleccionar() throws Exception {
        Map<UUID, ServiceRequestApplication> cancelled = requestWithApplicants();
        UUID cancelledId = cancelled.get(FIRST).getServiceRequestId();
        jdbcTemplate.update("UPDATE service_requests SET status = 'CANCELLED' WHERE id = ?", cancelledId);
        Map<UUID, ServiceRequestApplication> past = requestWithApplicants();
        UUID pastId = past.get(FIRST).getServiceRequestId();
        jdbcTemplate.update("UPDATE service_requests SET expected_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))), pastId);

        select(CLIENT, cancelledId, cancelled.get(FIRST).getId()).andExpect(status().isConflict());
        select(CLIENT, pastId, past.get(FIRST).getId()).andExpect(status().isConflict());

        assertThat(statuses(cancelledId).values()).containsOnly(ApplicationStatus.APPLIED);
        assertThat(statuses(pastId).values()).containsOnly(ApplicationStatus.APPLIED);
    }

    @Test
    void variasSeleccionesSimultaneasDejanUnSoloPrestador() throws Exception {
        Map<UUID, ServiceRequestApplication> applications = requestWithApplicants();
        UUID requestId = applications.get(FIRST).getServiceRequestId();
        List<UUID> providers = List.of(FIRST, SECOND, THIRD, FIRST, SECOND, THIRD);
        ExecutorService pool = Executors.newFixedThreadPool(providers.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statusCodes = new ArrayList<>();
        try {
            List<Future<Integer>> pending = new ArrayList<>();
            for (UUID provider : providers) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return select(CLIENT, requestId, applications.get(provider).getId())
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : pending) {
                statusCodes.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        // Gana un solo prestador. Los 200 son su selección y su propio duplicado (idempotente); el resto, 409.
        assertThat(statusCodes).filteredOn(code -> code == 200).hasSize(2);
        assertThat(statusCodes).filteredOn(code -> code == 409).hasSize(providers.size() - 2);
        assertThat(statuses(requestId).values().stream().filter(s -> s == ApplicationStatus.SELECTED).count())
                .isEqualTo(1);
        assertThat(statuses(requestId).values().stream().filter(s -> s == ApplicationStatus.REJECTED).count())
                .isEqualTo(2);
        UUID selected = serviceRequestRepository.findById(requestId).orElseThrow().getSelectedProviderId();
        assertThat(applicationsByProvider(requestId).get(selected).getStatus()).isEqualTo(ApplicationStatus.SELECTED);
    }

    // ---------- Privacidad entre clientes y postulaciones ajenas ----------

    @Test
    void unaSolicitudAjenaOInexistenteResponde404SinCambiarNada() throws Exception {
        Map<UUID, ServiceRequestApplication> applications = requestWithApplicants();
        UUID requestId = applications.get(FIRST).getServiceRequestId();
        UUID applicationId = applications.get(FIRST).getId();

        select(OTHER_CLIENT, requestId, applicationId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Solicitud no encontrada"))
                .andExpect(jsonPath("$.detail").value("No existe la solicitud indicada"));
        select(CLIENT, UUID.randomUUID(), applicationId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No existe la solicitud indicada"));
        mockMvc.perform(post("/api/services/requests/no-es-uuid/applications/" + applicationId + "/selection")
                        .with(client(CLIENT)))
                .andExpect(status().isNotFound());

        assertThat(statuses(requestId).values()).containsOnly(ApplicationStatus.APPLIED);
        assertThat(serviceRequestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.PUBLISHED);
    }

    @Test
    void unaPostulacionDeOtraSolicitudOInexistenteResponde404SinCambiarNada() throws Exception {
        Map<UUID, ServiceRequestApplication> applications = requestWithApplicants();
        UUID requestId = applications.get(FIRST).getServiceRequestId();
        ServiceRequest another = request(CLIENT);
        ServiceRequestApplication foreign = applied(another, SECOND, Instant.now());

        select(CLIENT, requestId, foreign.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Postulación no encontrada"));
        select(CLIENT, requestId, UUID.randomUUID()).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/services/requests/" + requestId + "/applications/no-es-uuid/selection")
                        .with(client(CLIENT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Postulación no encontrada"))
                .andExpect(jsonPath("$.detail").value("No existe la postulación indicada"));

        assertThat(statuses(requestId).values()).containsOnly(ApplicationStatus.APPLIED);
        assertThat(statuses(another.getId()).values()).containsOnly(ApplicationStatus.APPLIED);
        assertThat(serviceRequestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.PUBLISHED);
    }

    // ---------- Autorización ----------

    @Test
    void sinTokenResponde401YUnTecnicoResponde403() throws Exception {
        Map<UUID, ServiceRequestApplication> applications = requestWithApplicants();
        UUID requestId = applications.get(FIRST).getServiceRequestId();
        UUID applicationId = applications.get(FIRST).getId();

        mockMvc.perform(post(selection(requestId, applicationId))).andExpect(status().isUnauthorized());
        mockMvc.perform(post(selection(requestId, applicationId)).with(role(FIRST, "ROLE_PROFESSIONAL")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"));
        mockMvc.perform(post(selection(requestId, applicationId))
                        .with(jwt().jwt(token -> token.subject("no-es-usr"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT")))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        assertThat(statuses(requestId).values()).containsOnly(ApplicationStatus.APPLIED);
    }
}
