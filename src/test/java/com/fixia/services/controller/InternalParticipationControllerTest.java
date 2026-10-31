package com.fixia.services.controller;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.domain.ServiceRequestTransition;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ruta interna de participación (GC-444, contrato de GC-443): ms-users pregunta quién participó en una solicitud y
 * cuándo se cerró, sin leer esta base. El servicio cerrado se lleva hasta el cierre por la API, para que el historial
 * de transiciones sea el real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalParticipationControllerTest {

    private static final String KEY = "clave-interna-de-prueba";
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TECHNICIAN = UUID.randomUUID();
    private static final String ADDRESS = "Calle 10 # 5-20, apto 301";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private QuotationRepository quotationRepository;

    @Autowired
    private ServiceContractRepository contractRepository;

    @Autowired
    private ServiceRequestTransitionRepository transitionRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    private ServiceRequest published() {
        Instant now = Instant.now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero", 4.6512, -74.0583),
                new ExactLocation(ADDRESS, 4.65, -74.08), now.plus(Duration.ofDays(5)), new BigDecimal("200000"),
                CurrencyCode.COP, List.of(), now));
    }

    /** Contratado aceptando la cotización, iniciado, finalizado y cerrado, todo por la API. */
    private ServiceRequest closed() throws Exception {
        Instant now = Instant.now();
        ServiceRequest request = published();
        request.selectProvider(PROVIDER, now);
        ServiceRequest saved = serviceRequestRepository.saveAndFlush(request);
        Quotation quotation = quotationRepository.saveAndFlush(Quotation.send(saved, PROVIDER,
                new BigDecimal("150000.50"), CurrencyCode.COP, "Cambio de tubería", "Incluye materiales",
                now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)), 1, now));
        mockMvc.perform(post("/api/services/requests/" + saved.getId() + "/quotations/" + quotation.getId()
                + "/acceptance").with(user(CLIENT, "ROLE_CLIENT"))).andExpect(status().isOk());
        ServiceContract contract = contractRepository.findByServiceRequestId(saved.getId()).orElseThrow();
        mockMvc.perform(post("/api/services/contracts/" + contract.getId() + "/start")
                .with(user(PROVIDER, "ROLE_PROFESSIONAL"))).andExpect(status().isOk());
        mockMvc.perform(post("/api/services/contracts/" + contract.getId() + "/finish")
                .with(user(PROVIDER, "ROLE_PROFESSIONAL"))).andExpect(status().isOk());
        mockMvc.perform(post("/api/services/requests/" + saved.getId() + "/closure")
                .with(user(CLIENT, "ROLE_CLIENT"))).andExpect(status().isOk());
        return saved;
    }

    /** La solicitud con una postulación seleccionada del tipo indicado y otra rechazada. */
    private ServiceRequest selected(boolean company) {
        Instant now = Instant.now();
        ServiceRequest request = published();
        UUID providerId = company ? COMPANY : PROVIDER;
        ServiceRequestApplication chosen = company
                ? ServiceRequestApplication.submitForCompany(request, COMPANY, TECHNICIAN, new BigDecimal("180000"),
                CurrencyCode.COP, now)
                : ServiceRequestApplication.submit(request, PROVIDER, new BigDecimal("150000"), CurrencyCode.COP, now);
        ServiceRequestApplication other = ServiceRequestApplication.submit(request, UUID.randomUUID(),
                new BigDecimal("170000"), CurrencyCode.COP, now);
        chosen.select(now);
        other.reject(now);
        applicationRepository.saveAllAndFlush(List.of(other, chosen));
        request.selectProvider(providerId, now);
        return serviceRequestRepository.saveAndFlush(request);
    }

    private static RequestPostProcessor user(UUID id, String role) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority(role));
    }

    private ResultActions participation(Object requestId) throws Exception {
        return mockMvc.perform(get("/internal/services/" + requestId + "/participation")
                .header("X-Internal-Api-Key", KEY));
    }

    // ---------- Contenido ----------

    @Test
    void unServicioCerradoDevuelveSusParticipantesYElMomentoDelCierre() throws Exception {
        ServiceRequest request = closed();
        Instant closedAt = transitionRepository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(request.getId())
                .stream()
                .filter(transition -> transition.getToStatus() == ServiceRequestStatus.CLOSED)
                .map(ServiceRequestTransition::getOccurredAt)
                .findFirst().orElseThrow();

        participation(request.getId())
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.serviceRequestId").value(request.getId().toString()))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.clientId").value(CLIENT.toString()))
                .andExpect(jsonPath("$.providerId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.providerType").value("INDEPENDENT"))
                .andExpect(jsonPath("$.closedAt").value(closedAt.toString()))
                .andExpect(content().string(not(containsString(ADDRESS))))
                .andExpect(content().string(not(containsString("Fuga de agua"))))
                .andExpect(content().string(not(containsString("150000"))));
    }

    @Test
    void sinPrestadorNoHayProveedorNiCierre() throws Exception {
        ServiceRequest request = published();

        participation(request.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.clientId").value(CLIENT.toString()))
                .andExpect(jsonPath("$.providerId").value(nullValue()))
                .andExpect(jsonPath("$.providerType").value(nullValue()))
                .andExpect(jsonPath("$.closedAt").value(nullValue()));
    }

    @Test
    void elTipoDePrestadorSaleDeLaPostulacionSeleccionada() throws Exception {
        participation(selected(true).getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROVIDER_SELECTED"))
                .andExpect(jsonPath("$.providerId").value(COMPANY.toString()))
                .andExpect(jsonPath("$.providerType").value("COMPANY"))
                .andExpect(jsonPath("$.closedAt").value(nullValue()));

        cleanDatabase();
        participation(selected(false).getId())
                .andExpect(jsonPath("$.providerId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.providerType").value("INDEPENDENT"));
    }

    @Test
    void unCierreAnteriorAlHistorialNoInventaLaFecha() throws Exception {
        ServiceRequest request = published();
        request.selectProvider(PROVIDER, Instant.now());
        serviceRequestRepository.saveAndFlush(request);
        jdbcTemplate.update("UPDATE service_requests SET status = 'CLOSED' WHERE id = ?", request.getId());

        participation(request.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.providerType").value("INDEPENDENT"))
                .andExpect(jsonPath("$.closedAt").value(nullValue()));
    }

    @Test
    void unaSolicitudInexistenteOUnIdMalFormadoResponde404() throws Exception {
        participation(UUID.randomUUID()).andExpect(status().isNotFound());
        participation("no-es-un-uuid").andExpect(status().isNotFound());
    }

    // ---------- Seguridad ----------

    @Test
    void soloAceptaLaClaveInternaYNuncaUnTokenDeUsuario() throws Exception {
        UUID id = published().getId();
        String path = "/internal/services/" + id + "/participation";

        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header("X-Internal-Api-Key", "otra-clave")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header("X-Internal-Api-Key", "")).andExpect(status().isUnauthorized());
        // Esta cadena no interpreta tokens: un Bearer se ignora y la petición queda sin autenticar.
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer token-de-usuario"))
                .andExpect(status().isUnauthorized());
        // Un usuario ya autenticado (como lo deja el soporte de pruebas) no tiene el permiso interno.
        mockMvc.perform(get(path).with(user(CLIENT, "ROLE_CLIENT"))).andExpect(status().isForbidden());
        participation(id).andExpect(status().isOk());
    }

    @Test
    void lasRutasDeUsuarioSiguenIgualConLaCadenaInternaPresente() throws Exception {
        mockMvc.perform(get("/api/services/requests").header("X-Internal-Api-Key", KEY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/services/requests").with(user(CLIENT, "ROLE_CLIENT")))
                .andExpect(status().isOk());
    }
}
