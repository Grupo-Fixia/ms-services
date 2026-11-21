package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.client.CompanyClient;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.client.TechnicianProfileClient;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GC-551 ({@code ms-users/docs/contracts/empresas.md} §8): cuando el cliente selecciona a una empresa, el técnico designado
 * cotiza y ejecuta; la empresa sigue siendo la prestadora (recibe el pago) y solo consulta. Recorre el ciclo completo con
 * ms-users simulado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanyExecutionFlowTest {

    private static final String KEY = "clave-interna-de-prueba";

    private final UUID client = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();
    private final UUID carlos = UUID.randomUUID(); // técnico designado
    private final UUID ana = UUID.randomUUID();    // otro técnico de la misma empresa
    private final UUID stranger = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRequestRepository requestRepository;

    @Autowired
    private QuotationRepository quotationRepository;

    @Autowired
    private ServiceContractRepository contractRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private TechnicianEligibilityClient eligibilityClient;

    @MockBean
    private CompanyClient companyClient;

    @MockBean
    private TechnicianProfileClient profileClient;

    @BeforeEach
    void setUp() {
        when(eligibilityClient.check(any(), any()))
                .thenReturn(new TechnicianEligibilityClient.Result(UUID.randomUUID(), true, List.of()));
        when(companyClient.isTechnicianAssociated(any(), any())).thenAnswer(invocation ->
                company.equals(invocation.getArgument(0))
                        && List.of(carlos, ana).contains(invocation.<UUID>getArgument(1)));
        when(companyClient.findPublicProfiles(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ids.stream().collect(Collectors.toMap(Function.identity(), id -> new CompanyClient.CompanyProfile(
                    id, "Servicios Andina", new TechnicianProfileClient.Reputation(new BigDecimal("4.5"), 12))));
        });
        when(profileClient.findPublicProfiles(any())).thenAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ids.stream().collect(Collectors.toMap(Function.identity(),
                    id -> new TechnicianProfileClient.PublicProfile(id, "Carlos G.", "VALID",
                            List.of(ServiceCategory.PLUMBING), "Plomero", 8, "Bogotá", null)));
        });
    }

    // ---------- Utilidades ----------

    private static RequestPostProcessor as(UUID userId, String role) {
        return jwt().jwt(token -> token.subject("usr_" + userId)).authorities(new SimpleGrantedAuthority(role));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ServiceRequest published() {
        Instant now = Instant.now();
        return requestRepository.saveAndFlush(ServiceRequest.publish(client, ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero", 4.65, -74.08), null,
                now.plus(Duration.ofDays(2)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now));
    }

    /** La empresa se postula con Carlos y el cliente la selecciona. */
    private UUID companySelected() throws Exception {
        UUID requestId = published().getId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceRequestId", requestId);
        body.put("proposedAmount", 180000);
        body.put("currency", "COP");
        body.put("technicianId", carlos);
        UUID applicationId = UUID.fromString(json(mockMvc.perform(post("/api/services/applications")
                        .with(as(company, "ROLE_COMPANY")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))).andExpect(status().isCreated()))
                .get("id").asText());
        mockMvc.perform(post("/api/services/requests/" + requestId + "/applications/" + applicationId + "/selection")
                .with(as(client, "ROLE_CLIENT"))).andExpect(status().isOk());
        return requestId;
    }

    private ResultActions quote(UUID technician, String role, UUID requestId, Instant agreedAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceRequestId", requestId);
        body.put("amount", 180000);
        body.put("currency", "COP");
        body.put("scope", "Cambio de tubería");
        body.put("conditions", "Materiales incluidos");
        body.put("agreedAt", agreedAt.toString());
        body.put("validUntil", Instant.now().plus(Duration.ofDays(1)).toString());
        return mockMvc.perform(post("/api/services/quotations").with(as(technician, role))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions accept(UUID requestId, UUID quotationId) throws Exception {
        return mockMvc.perform(post("/api/services/requests/" + requestId + "/quotations/" + quotationId
                + "/acceptance").with(as(client, "ROLE_CLIENT")));
    }

    private Instant inThreeDays() {
        return Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);
    }

    // ---------- Ciclo completo ----------

    @Test
    void elTecnicoDesignadoCotizaYEjecutaYLaEmpresaSoloConsulta() throws Exception {
        UUID requestId = companySelected();

        // La comparación muestra la reputación de la empresa (promedio de sus técnicos, calculado en ms-users).
        mockMvc.perform(get("/api/services/requests/" + requestId + "/applications").with(as(client, "ROLE_CLIENT")))
                .andExpect(jsonPath("$[0].provider.reputation.average").value(4.5));

        // Cotiza Carlos en nombre de la empresa.
        UUID quotationId = UUID.fromString(json(quote(carlos, "ROLE_PROFESSIONAL", requestId, inThreeDays())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerId").value(company.toString()))
                .andExpect(jsonPath("$.authorId").value(carlos.toString()))).get("id").asText());
        mockMvc.perform(get("/api/services/quotations").with(as(carlos, "ROLE_PROFESSIONAL")))
                .andExpect(jsonPath("$[0].id").value(quotationId.toString()));

        // El cliente contrata: prestadora la empresa, ejecutor Carlos.
        accept(requestId, quotationId).andExpect(status().isOk());
        ServiceContract contract = contractRepository.findByServiceRequestId(requestId).orElseThrow();
        assertThat(contract.getProviderId()).isEqualTo(company);
        assertThat(contract.getExecutorId()).isEqualTo(carlos);
        ContractPayments.hold(jdbc, contract.getId());
        String contractPath = "/api/services/contracts/" + contract.getId();

        // La empresa consulta, pero no ejecuta; Ana (misma empresa, no designada) no ve el contrato.
        mockMvc.perform(get(contractPath).with(as(company, "ROLE_COMPANY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value(company.toString()))
                .andExpect(jsonPath("$.executorId").value(carlos.toString()));
        mockMvc.perform(get("/api/services/contracts").with(as(company, "ROLE_COMPANY")))
                .andExpect(jsonPath("$[0].id").value(contract.getId().toString()));
        mockMvc.perform(post(contractPath + "/start").with(as(company, "ROLE_COMPANY")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(contractPath + "/start").with(as(ana, "ROLE_PROFESSIONAL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(contractPath).with(as(ana, "ROLE_PROFESSIONAL"))).andExpect(status().isNotFound());

        // Carlos ejecuta.
        mockMvc.perform(get("/api/services/contracts").with(as(carlos, "ROLE_PROFESSIONAL")))
                .andExpect(jsonPath("$[0].id").value(contract.getId().toString()));
        mockMvc.perform(post(contractPath + "/start").with(as(carlos, "ROLE_PROFESSIONAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("IN_PROGRESS"));
        mockMvc.perform(get(contractPath + "/evidences").with(as(company, "ROLE_COMPANY")))
                .andExpect(status().isOk());
        mockMvc.perform(post(contractPath + "/finish").with(as(carlos, "ROLE_PROFESSIONAL")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/services/requests/" + requestId + "/closure").with(as(client, "ROLE_CLIENT")))
                .andExpect(status().isOk());
        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.CLOSED);

        // ms-users evalúa al ejecutor.
        mockMvc.perform(get("/internal/services/" + requestId + "/participation").header("X-Internal-Api-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value(company.toString()))
                .andExpect(jsonPath("$.providerType").value("COMPANY"))
                .andExpect(jsonPath("$.executorId").value(carlos.toString()));

        // Historial: Carlos ve lo que ejecutó; la empresa, lo que prestó.
        mockMvc.perform(get("/api/services/history").with(as(carlos, "ROLE_PROFESSIONAL")))
                .andExpect(jsonPath("$.items[0].serviceRequestId").value(requestId.toString()));
        mockMvc.perform(get("/api/services/history").with(as(company, "ROLE_COMPANY")))
                .andExpect(jsonPath("$.items[0].serviceRequestId").value(requestId.toString()));

        // Eventos válidos con su esquema, con la empresa como prestadora y Carlos como ejecutor y actor.
        List<OutboxEvent> events = outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(requestId);
        for (OutboxEvent event : events) {
            assertThat(EventContract.violations(objectMapper.readTree(event.getPayload())))
                    .as(event.getEventType()).isEmpty();
        }
        for (String type : List.of("cotizacion.enviada", "servicio.contratado", "servicio.iniciado",
                "servicio.finalizado")) {
            JsonNode payload = objectMapper.readTree(events.stream().filter(e -> e.getEventType().equals(type))
                    .findFirst().orElseThrow().getPayload());
            assertThat(payload.at("/data/providerId").asText()).as(type).isEqualTo(company.toString());
            assertThat(payload.at("/data/executorId").asText()).as(type).isEqualTo(carlos.toString());
        }
        JsonNode started = objectMapper.readTree(events.stream()
                .filter(e -> e.getEventType().equals("servicio.iniciado")).findFirst().orElseThrow().getPayload());
        assertThat(started.at("/actor/userId").asText()).isEqualTo(carlos.toString());
    }

    // ---------- Quién puede cotizar ----------

    @Test
    void soloElTecnicoDesignadoCotiza() throws Exception {
        UUID requestId = companySelected();

        quote(company, "ROLE_COMPANY", requestId, inThreeDays()).andExpect(status().isForbidden());
        quote(ana, "ROLE_PROFESSIONAL", requestId, inThreeDays())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("No eres el técnico designado de esta solicitud"));
        // Un técnico ajeno a la empresa recibe el 409 genérico: no se le dice que hay una empresa seleccionada.
        quote(stranger, "ROLE_PROFESSIONAL", requestId, inThreeDays())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("No tienes una selección vigente en esta solicitud"));
        assertThat(quotationRepository.findByServiceRequestIdOrderByRevisionDesc(requestId)).isEmpty();
    }

    // ---------- El técnico se desvinculó ----------

    @Test
    void siElTecnicoYaNoEstaEnLaEmpresaNoCotiza() throws Exception {
        UUID requestId = companySelected();
        when(companyClient.isTechnicianAssociated(company, carlos)).thenReturn(false);

        quote(carlos, "ROLE_PROFESSIONAL", requestId, inThreeDays())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("El técnico propuesto ya no pertenece a la empresa"));
    }

    @Test
    void siElTecnicoSeVaAntesDeQueElClienteAcepteNoSeContrata() throws Exception {
        UUID requestId = companySelected();
        UUID quotationId = UUID.fromString(json(quote(carlos, "ROLE_PROFESSIONAL", requestId, inThreeDays())
                .andExpect(status().isCreated())).get("id").asText());
        when(companyClient.isTechnicianAssociated(company, carlos)).thenReturn(false);

        accept(requestId, quotationId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("El técnico propuesto ya no pertenece a la empresa"));
        assertThat(quotationRepository.findById(quotationId).orElseThrow().getStatus()).isEqualTo(QuotationStatus.SENT);
        assertThat(contractRepository.findByServiceRequestId(requestId)).isEmpty();
    }

    @Test
    void siElTecnicoSeVaAntesDeLaSeleccionNoSeSelecciona() throws Exception {
        UUID requestId = published().getId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceRequestId", requestId);
        body.put("proposedAmount", 180000);
        body.put("currency", "COP");
        body.put("technicianId", carlos);
        UUID applicationId = UUID.fromString(json(mockMvc.perform(post("/api/services/applications")
                        .with(as(company, "ROLE_COMPANY")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))).andExpect(status().isCreated()))
                .get("id").asText());
        when(companyClient.isTechnicianAssociated(company, carlos)).thenReturn(false);

        mockMvc.perform(post("/api/services/requests/" + requestId + "/applications/" + applicationId + "/selection")
                        .with(as(client, "ROLE_CLIENT")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("El técnico propuesto ya no pertenece a la empresa"));
        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.PUBLISHED);
    }

    // ---------- Horario: es del técnico, no de la empresa ----------

    @Test
    void elHorarioQueCuentaEsElDelTecnicoQueEjecuta() throws Exception {
        Instant agreedAt = inThreeDays();
        // Carlos ya tiene un servicio de la empresa contratado a esa hora.
        UUID first = companySelected();
        UUID firstQuotation = UUID.fromString(json(quote(carlos, "ROLE_PROFESSIONAL", first, agreedAt)
                .andExpect(status().isCreated())).get("id").asText());
        accept(first, firstQuotation).andExpect(status().isOk());

        UUID second = companySelected();
        quote(carlos, "ROLE_PROFESSIONAL", second, agreedAt.plus(Duration.ofMinutes(30)))
                .andExpect(status().isConflict());
        // Con otro horario sí puede.
        quote(carlos, "ROLE_PROFESSIONAL", second, agreedAt.plus(Duration.ofHours(5)))
                .andExpect(status().isCreated());
    }
}
