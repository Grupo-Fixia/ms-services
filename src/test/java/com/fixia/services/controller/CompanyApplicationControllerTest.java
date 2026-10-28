package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.client.CompanyClient;
import com.fixia.services.client.EligibilityCheckException;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.client.TechnicianProfileClient;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.ProviderType;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.service.EventContract;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Postulación y selección empresarial (GC-468, historia GC-465, CA-33): una empresa se postula una vez por solicitud
 * con un técnico propio y un precio; el cliente la ve como empresa con su ejecutor y la selecciona por la ruta normal.
 * La asociación, la elegibilidad y los perfiles de ms-users se simulan con dobles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanyApplicationControllerTest {

    private static final String APPLICATIONS = "/api/services/applications";
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID OTHER_COMPANY = UUID.randomUUID();
    private static final UUID CARLOS = UUID.randomUUID();
    private static final UUID ANA = UUID.randomUUID();
    private static final UUID INDEPENDENT = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TechnicianEligibilityClient eligibilityClient;

    @MockBean
    private CompanyClient companyClient;

    @MockBean
    private TechnicianProfileClient profileClient;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
        when(eligibilityClient.check(any(), any()))
                .thenReturn(new TechnicianEligibilityClient.Result(UUID.randomUUID(), true, List.of()));
        when(companyClient.isTechnicianAssociated(any(), any())).thenReturn(true);
        when(companyClient.findPublicProfiles(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ids.stream().collect(Collectors.toMap(Function.identity(),
                    id -> new CompanyClient.CompanyProfile(id, "Servicios Andina")));
        });
        when(profileClient.findPublicProfiles(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ids.stream().collect(Collectors.toMap(Function.identity(),
                    id -> new TechnicianProfileClient.PublicProfile(id, id.equals(CARLOS) ? "Carlos G." : "Ana R.",
                            "PENDING", List.of(ServiceCategory.PLUMBING), "Plomero", 8, "Bogotá")));
        });
    }

    // ---------- Utilidades ----------

    private static RequestPostProcessor as(UUID userId, String role) {
        return jwt().jwt(token -> token.subject("usr_" + userId)).authorities(new SimpleGrantedAuthority(role));
    }

    private ServiceRequest publishedRequest() {
        Instant now = Instant.now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero", 4.65, -74.08), null,
                now.plus(Duration.ofDays(2)), new java.math.BigDecimal("200000"),
                com.fixia.services.domain.CurrencyCode.COP, List.of(), now));
    }

    private ResultActions apply(UUID applicant, String role, UUID requestId, UUID technicianId, Object price)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceRequestId", requestId);
        body.put("proposedAmount", price);
        body.put("currency", "COP");
        if (technicianId != null) {
            body.put("technicianId", technicianId);
        }
        return mockMvc.perform(post(APPLICATIONS).with(as(applicant, role)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions applyAsCompany(UUID company, UUID requestId, UUID technicianId, Object price)
            throws Exception {
        return apply(company, "ROLE_COMPANY", requestId, technicianId, price);
    }

    private UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .get("id").asText());
    }

    private JsonNode lastEvent(UUID requestId) throws Exception {
        List<OutboxEvent> events = outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(requestId);
        return objectMapper.readTree(events.get(events.size() - 1).getPayload());
    }

    // ---------- Postularse como empresa ----------

    @Test
    void laEmpresaSePostulaConSuTecnicoYSuPrecio() throws Exception {
        ServiceRequest request = publishedRequest();

        UUID id = idOf(applyAsCompany(COMPANY, request.getId(), CARLOS, 180000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerType").value("COMPANY"))
                .andExpect(jsonPath("$.technicianId").value(CARLOS.toString()))
                .andExpect(jsonPath("$.proposedAmount").value(180000.0)));

        ServiceRequestApplication saved = applicationRepository.findById(id).orElseThrow();
        assertThat(saved.getProviderId()).isEqualTo(COMPANY);
        assertThat(saved.getProviderType()).isEqualTo(ProviderType.COMPANY);
        assertThat(saved.getTechnicianId()).isEqualTo(CARLOS);
        verify(companyClient).isTechnicianAssociated(COMPANY, CARLOS);
        // La elegibilidad que cuenta es la del técnico propuesto, no la de la empresa.
        verify(eligibilityClient).check(eq(CARLOS), any(ServiceRequest.class));

        JsonNode event = lastEvent(request.getId());
        assertThat(event.get("eventType").asText()).isEqualTo("postulacion.registrada");
        assertThat(event.at("/actor/role").asText()).isEqualTo("COMPANY");
        assertThat(event.at("/actor/userId").asText()).isEqualTo(COMPANY.toString());
        assertThat(event.at("/data/providerType").asText()).isEqualTo("COMPANY");
        assertThat(event.at("/data/technicianId").asText()).isEqualTo(CARLOS.toString());
        assertThat(EventContract.violations(event)).isEmpty();
    }

    @Test
    void elIndependienteSigueIgualYSuEventoLoIdentificaComoTal() throws Exception {
        ServiceRequest request = publishedRequest();

        apply(INDEPENDENT, "ROLE_PROFESSIONAL", request.getId(), null, 150000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerType").value("INDEPENDENT"))
                .andExpect(jsonPath("$.technicianId").value(nullValue()));

        verifyNoInteractions(companyClient);
        JsonNode event = lastEvent(request.getId());
        assertThat(event.at("/data/providerType").asText()).isEqualTo("INDEPENDENT");
        assertThat(event.at("/data/technicianId").isNull()).isTrue();
        assertThat(EventContract.violations(event)).isEmpty();
    }

    @Test
    void laEmpresaDebeProponerUnTecnicoYElIndependienteNo() throws Exception {
        ServiceRequest request = publishedRequest();

        applyAsCompany(COMPANY, request.getId(), null, 180000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Postulación inválida"))
                .andExpect(jsonPath("$.detail").value("La empresa debe indicar el técnico que haría el trabajo"));
        apply(INDEPENDENT, "ROLE_PROFESSIONAL", request.getId(), CARLOS, 150000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Un técnico independiente no propone a otro técnico"));

        verifyNoInteractions(companyClient, eligibilityClient);
        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void unTecnicoQueNoEsDeLaEmpresaNoSePuedeProponer() throws Exception {
        ServiceRequest request = publishedRequest();
        when(companyClient.isTechnicianAssociated(COMPANY, ANA)).thenReturn(false);

        applyAsCompany(COMPANY, request.getId(), ANA, 180000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasons", contains("TECHNICIAN_NOT_ASSOCIATED")));

        verify(eligibilityClient, never()).check(any(), any());
        assertThat(applicationRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void siElTecnicoPropuestoNoEsElegibleSeRechazaConSusMotivos() throws Exception {
        ServiceRequest request = publishedRequest();
        when(eligibilityClient.check(eq(CARLOS), any())).thenReturn(
                new TechnicianEligibilityClient.Result(request.getId(), false, List.of("ZONE")));

        applyAsCompany(COMPANY, request.getId(), CARLOS, 180000)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasons", contains("ZONE")));

        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void siNoSePuedeVerificarLaAsociacionNoSePostula() throws Exception {
        ServiceRequest request = publishedRequest();
        when(companyClient.isTechnicianAssociated(any(), any()))
                .thenThrow(new EligibilityCheckException("ms-users caído"));

        applyAsCompany(COMPANY, request.getId(), CARLOS, 180000)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Elegibilidad no verificable"));

        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void unaEmpresaSePostulaUnaSolaVezAunqueCambieDeTecnicoOPrecio() throws Exception {
        ServiceRequest request = publishedRequest();
        UUID first = idOf(applyAsCompany(COMPANY, request.getId(), CARLOS, 180000)
                .andExpect(status().isCreated()));

        applyAsCompany(COMPANY, request.getId(), ANA, 150000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Postulación duplicada"))
                .andExpect(jsonPath("$.applicationId").value(first.toString()));
        applyAsCompany(COMPANY, request.getId(), CARLOS, 180000).andExpect(status().isConflict());

        assertThat(applicationRepository.count()).isEqualTo(1);
        assertThat(applicationRepository.findById(first).orElseThrow().getTechnicianId()).isEqualTo(CARLOS);
        applyAsCompany(OTHER_COMPANY, request.getId(), ANA, 170000).andExpect(status().isCreated());
    }

    @Test
    void peticionesSimultaneasDeLaMismaEmpresaDejanUnaSolaPostulacion() throws Exception {
        ServiceRequest request = publishedRequest();
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> pending = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                UUID technician = i % 2 == 0 ? CARLOS : ANA;
                pending.add(pool.submit(() -> {
                    start.await();
                    return applyAsCompany(COMPANY, request.getId(), technician, 180000)
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> future : pending) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(code -> code == 201).hasSize(1);
        assertThat(applicationRepository.count()).isEqualTo(1);
    }

    @Test
    void laEmpresaConsultaSusPostulaciones() throws Exception {
        ServiceRequest request = publishedRequest();
        UUID id = idOf(applyAsCompany(COMPANY, request.getId(), CARLOS, 180000).andExpect(status().isCreated()));

        mockMvc.perform(get(APPLICATIONS).with(as(COMPANY, "ROLE_COMPANY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].technicianId").value(CARLOS.toString()));
        mockMvc.perform(get(APPLICATIONS + "/" + id).with(as(OTHER_COMPANY, "ROLE_COMPANY")))
                .andExpect(status().isNotFound());
    }

    // ---------- Lo que ve y hace el cliente ----------

    @Test
    void elClienteVeEmpresaYEjecutorComoIdentidadesDistintasYLaSeleccionaPorLaRutaNormal() throws Exception {
        ServiceRequest request = publishedRequest();
        UUID independent = idOf(apply(INDEPENDENT, "ROLE_PROFESSIONAL", request.getId(), null, 150000)
                .andExpect(status().isCreated()));
        UUID company = idOf(applyAsCompany(COMPANY, request.getId(), CARLOS, 180000)
                .andExpect(status().isCreated()));

        mockMvc.perform(get("/api/services/requests/" + request.getId() + "/applications").with(as(CLIENT,
                        "ROLE_CLIENT")))
                // Se busca por tipo y no por posición: el orden de llegada depende del reloj (en Windows avanza a
                // saltos de milisegundos y dos postulaciones seguidas pueden quedar con el mismo instante).
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.providerType == 'INDEPENDENT')].providerId",
                        contains(INDEPENDENT.toString())))
                .andExpect(jsonPath("$[?(@.providerType == 'INDEPENDENT')].executor", contains(nullValue())))
                .andExpect(jsonPath("$[?(@.providerType == 'COMPANY')].providerId", contains(COMPANY.toString())))
                .andExpect(jsonPath("$[?(@.providerType == 'COMPANY')].technicianId", contains(CARLOS.toString())))
                .andExpect(jsonPath("$[?(@.providerType == 'COMPANY')].provider.displayName",
                        contains("Servicios Andina")))
                .andExpect(jsonPath("$[?(@.providerType == 'COMPANY')].executor.displayName",
                        contains("Carlos G.")))
                .andExpect(jsonPath("$[?(@.providerType == 'COMPANY')].proposedAmount", contains(180000.0)));

        mockMvc.perform(post("/api/services/requests/" + request.getId() + "/applications/" + company
                        + "/selection").with(as(CLIENT, "ROLE_CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROVIDER_SELECTED"))
                .andExpect(jsonPath("$.selectedProviderId").value(COMPANY.toString()));

        ServiceRequest selected = serviceRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(selected.getStatus()).isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
        assertThat(selected.getSelectedProviderId()).isEqualTo(COMPANY);
        assertThat(applicationRepository.findById(company).orElseThrow().getTechnicianId()).isEqualTo(CARLOS);
        assertThat(applicationRepository.findById(independent).orElseThrow().getStatus().name())
                .isEqualTo("REJECTED");

        JsonNode event = lastEvent(request.getId());
        assertThat(event.get("eventType").asText()).isEqualTo("prestador.seleccionado");
        assertThat(event.at("/data/selectedProviderType").asText()).isEqualTo("COMPANY");
        assertThat(event.at("/data/selectedTechnicianId").asText()).isEqualTo(CARLOS.toString());
        assertThat(EventContract.violations(event)).isEmpty();
    }

    @Test
    void unaEmpresaQueMsUsersYaNoReconoceSaleSinPerfilPeroConSuEjecutor() throws Exception {
        ServiceRequest request = publishedRequest();
        applyAsCompany(COMPANY, request.getId(), CARLOS, 180000).andExpect(status().isCreated());
        doReturn(Map.of()).when(companyClient).findPublicProfiles(any());

        mockMvc.perform(get("/api/services/requests/" + request.getId() + "/applications").with(as(CLIENT,
                        "ROLE_CLIENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value(nullValue()))
                .andExpect(jsonPath("$[0].executor.displayName").value("Carlos G."));
    }

    @Test
    void sinEmpresasPostuladasNoSePidenPerfilesDeEmpresaYSiFallanResponde503() throws Exception {
        ServiceRequest request = publishedRequest();
        apply(INDEPENDENT, "ROLE_PROFESSIONAL", request.getId(), null, 150000).andExpect(status().isCreated());
        String applicants = "/api/services/requests/" + request.getId() + "/applications";

        mockMvc.perform(get(applicants).with(as(CLIENT, "ROLE_CLIENT"))).andExpect(status().isOk());
        verify(companyClient, never()).findPublicProfiles(any());

        applyAsCompany(COMPANY, request.getId(), CARLOS, 180000).andExpect(status().isCreated());
        doThrow(new com.fixia.services.client.ProfileLookupException("caído")).when(companyClient)
                .findPublicProfiles(any());
        mockMvc.perform(get(applicants).with(as(CLIENT, "ROLE_CLIENT")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Perfiles no disponibles"));
    }

    // ---------- Después de seleccionar y por rol ----------

    @Test
    void despuesDeSeleccionarLaEmpresaNoSeCotizaNiPorLaEmpresaNiPorSuTecnico() throws Exception {
        ServiceRequest request = publishedRequest();
        UUID company = idOf(applyAsCompany(COMPANY, request.getId(), CARLOS, 180000)
                .andExpect(status().isCreated()));
        mockMvc.perform(post("/api/services/requests/" + request.getId() + "/applications/" + company
                + "/selection").with(as(CLIENT, "ROLE_CLIENT"))).andExpect(status().isOk());
        Instant now = Instant.now();
        Map<String, Object> quotation = new LinkedHashMap<>();
        quotation.put("serviceRequestId", request.getId());
        quotation.put("amount", 180000);
        quotation.put("currency", "COP");
        quotation.put("scope", "Cambio de tubería");
        quotation.put("conditions", "Materiales incluidos");
        quotation.put("agreedAt", now.plus(Duration.ofDays(3)).toString());
        quotation.put("validUntil", now.plus(Duration.ofDays(1)).toString());
        String body = objectMapper.writeValueAsString(quotation);

        mockMvc.perform(post("/api/services/quotations").with(as(COMPANY, "ROLE_COMPANY"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/services/quotations").with(as(CARLOS, "ROLE_PROFESSIONAL"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void elClienteNoSePostulaYLaEmpresaNoUsaRutasDelClienteNiDelTecnico() throws Exception {
        ServiceRequest request = publishedRequest();

        apply(CLIENT, "ROLE_CLIENT", request.getId(), null, 150000).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/services/requests").with(as(COMPANY, "ROLE_COMPANY")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/services/opportunities").with(as(COMPANY, "ROLE_COMPANY")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/services/contracts").with(as(COMPANY, "ROLE_COMPANY")))
                .andExpect(status().isForbidden());
    }

    // ---------- Base de datos ----------

    @Test
    void laBaseExigeTecnicoSoloEnLasPostulacionesDeEmpresa() throws Exception {
        ServiceRequest request = publishedRequest();
        UUID independent = idOf(apply(INDEPENDENT, "ROLE_PROFESSIONAL", request.getId(), null, 150000)
                .andExpect(status().isCreated()));
        UUID company = idOf(applyAsCompany(COMPANY, request.getId(), CARLOS, 180000)
                .andExpect(status().isCreated()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE applications SET technician_id = ? WHERE id = ?", CARLOS, independent))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE applications SET technician_id = NULL WHERE id = ?", company))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE applications SET technician_id = provider_id WHERE id = ?", company))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE applications SET provider_type = 'OTRO' WHERE id = ?", independent))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
