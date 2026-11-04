package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.client.TechnicianProfileClient;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Historial propio de servicios (GC-448) a lo largo del flujo real por la API: publicar, postular, seleccionar,
 * cotizar, aceptar, iniciar, finalizar, objetar y cerrar. Tras cada paso el historial del cliente y el del prestador
 * coincide entre sí y con el detalle de la contratación que ya existe, y consultarlo de nuevo («recargar») da lo mismo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoryFlowTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER = UUID.randomUUID();
    private static final String EXACT_ADDRESS = "Calle 10 # 5-20, apto 301";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @MockBean
    private TechnicianEligibilityClient eligibilityClient;

    /** El listado de postulantes pide el perfil público a ms-users; aquí no se evalúa ese contenido. */
    @MockBean
    private TechnicianProfileClient profileClient;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAllInBatch();
        transitionRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
        when(eligibilityClient.check(any(), any()))
                .thenReturn(new TechnicianEligibilityClient.Result(UUID.randomUUID(), true, List.of()));
    }

    // ---------- Utilidades ----------

    private record Service(UUID requestId, UUID contractId) {
    }

    private static RequestPostProcessor as(UUID userId, String role) {
        return jwt().jwt(token -> token.subject("usr_" + userId)).authorities(new SimpleGrantedAuthority(role));
    }

    private ResultActions send(String path, UUID userId, String role, Object body) throws Exception {
        var request = post(path).with(as(userId, role));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode getJson(String path, UUID userId, String role) throws Exception {
        return json(mockMvc.perform(get(path).with(as(userId, role))).andExpect(status().isOk()));
    }

    /** Publica, postula, selecciona, cotiza y acepta por la API; el servicio queda contratado. */
    private Service contract(UUID clientId, UUID providerId, String quoted) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "PLUMBING");
        body.put("description", "Fuga de agua debajo del lavamanos");
        body.put("approximateLocation", Map.of("city", "Bogotá", "zone", "Chapinero",
                "latitude", 4.6533, "longitude", -74.0836));
        body.put("exactLocation", Map.of("address", EXACT_ADDRESS, "latitude", 4.7110, "longitude", -74.0721));
        body.put("expectedAt", Instant.now().plus(Duration.ofDays(2)).toString());
        body.put("budgetAmount", 200000);
        body.put("budgetCurrency", "COP");
        UUID request = UUID.fromString(json(send("/api/services/requests", clientId, "ROLE_CLIENT", body)
                .andExpect(status().isCreated())).get("id").asText());
        UUID application = UUID.fromString(json(send("/api/services/applications", providerId, "ROLE_PROFESSIONAL",
                Map.of("serviceRequestId", request, "proposedAmount", "98500.50", "currency", "COP"))
                .andExpect(status().isCreated())).get("id").asText());
        send("/api/services/requests/" + request + "/applications/" + application + "/selection", clientId,
                "ROLE_CLIENT", null).andExpect(status().isOk());
        Instant now = Instant.now();
        Map<String, Object> quotation = new LinkedHashMap<>();
        quotation.put("serviceRequestId", request);
        quotation.put("amount", quoted);
        quotation.put("currency", "COP");
        quotation.put("scope", "Cambio de la tubería del lavamanos");
        quotation.put("conditions", "Materiales incluidos");
        quotation.put("agreedAt", now.plus(Duration.ofDays(3)).toString());
        quotation.put("validUntil", now.plus(Duration.ofDays(1)).toString());
        UUID quotationId = UUID.fromString(json(send("/api/services/quotations", providerId, "ROLE_PROFESSIONAL",
                quotation).andExpect(status().isCreated())).get("id").asText());
        send("/api/services/requests/" + request + "/quotations/" + quotationId + "/acceptance", clientId,
                "ROLE_CLIENT", null).andExpect(status().isOk());
        UUID contractId = UUID.fromString(getJson("/api/services/requests/" + request + "/contract", clientId,
                "ROLE_CLIENT").get("id").asText());
        return new Service(request, contractId);
    }

    private JsonNode historyOf(UUID userId, String role) throws Exception {
        return getJson("/api/services/history", userId, role);
    }

    /**
     * El servicio tal como lo listan el cliente y el prestador: los dos coinciden entre sí y con el detalle de la
     * contratación (estado, importe, ids y último cambio), y repetir la consulta devuelve exactamente lo mismo.
     */
    private JsonNode assertConsistent(Service service, String expectedStatus) throws Exception {
        JsonNode clientPage = historyOf(CLIENT, "ROLE_CLIENT");
        JsonNode providerPage = historyOf(PROVIDER, "ROLE_PROFESSIONAL");
        JsonNode client = itemOf(clientPage, service);
        JsonNode provider = itemOf(providerPage, service);
        JsonNode clientDetail = getJson("/api/services/requests/" + service.requestId() + "/contract", CLIENT,
                "ROLE_CLIENT");
        JsonNode providerDetail = getJson("/api/services/contracts/" + service.contractId(), PROVIDER,
                "ROLE_PROFESSIONAL");

        assertThat(client.get("status").asText()).isEqualTo(expectedStatus);
        assertThat(provider.get("status").asText()).isEqualTo(expectedStatus);
        assertThat(clientDetail.get("serviceStatus").asText()).isEqualTo(expectedStatus);
        assertThat(providerDetail.get("serviceStatus").asText()).isEqualTo(expectedStatus);
        assertThat(client.get("role").asText()).isEqualTo("CLIENT");
        assertThat(provider.get("role").asText()).isEqualTo("PROFESSIONAL");
        assertThat(client.get("counterpartId").asText()).isEqualTo(PROVIDER.toString());
        assertThat(provider.get("counterpartId").asText()).isEqualTo(CLIENT.toString());
        assertThat(client.get("contractedAmount").decimalValue())
                .isEqualByComparingTo(clientDetail.get("amount").decimalValue());
        assertThat(client.get("contractedAmount").decimalValue())
                .isEqualByComparingTo(providerDetail.get("amount").decimalValue());
        assertThat(client.get("budgetAmount").decimalValue()).isEqualByComparingTo("200000");
        JsonNode timeline = clientDetail.get("timeline");
        assertThat(Instant.parse(client.get("lastTransitionAt").asText()))
                .isEqualTo(Instant.parse(timeline.get(timeline.size() - 1).get("occurredAt").asText()));
        // el prestador y el cliente ven las mismas fechas del mismo servicio
        for (String date : List.of("contractedAt", "startedAt", "finishedAt", "closedAt", "lastTransitionAt")) {
            assertThat(provider.get(date)).as(date).isEqualTo(client.get(date));
        }
        // recargar: la misma consulta devuelve lo mismo
        assertThat(historyOf(CLIENT, "ROLE_CLIENT")).isEqualTo(clientPage);
        assertThat(historyOf(PROVIDER, "ROLE_PROFESSIONAL")).isEqualTo(providerPage);
        return client;
    }

    private JsonNode itemOf(JsonNode page, Service service) {
        for (JsonNode item : page.get("items")) {
            if (item.get("contractId").asText().equals(service.contractId().toString())) {
                assertThat(item.get("serviceRequestId").asText()).isEqualTo(service.requestId().toString());
                return item;
            }
        }
        throw new AssertionError("El servicio " + service.contractId() + " no está en el historial: " + page);
    }

    private static String text(JsonNode node, String field) {
        return node.get(field).isNull() ? null : node.get(field).asText();
    }

    // ---------- Flujo completo ----------

    @Test
    void elHistorialSigueElCicloCompletoDelServicioConObjecionYCierre() throws Exception {
        Service service = contract(CLIENT, PROVIDER, "105000");
        String base = "/api/services/contracts/" + service.contractId();

        JsonNode contracted = assertConsistent(service, "CONTRACTED");
        assertThat(text(contracted, "startedAt")).isNull();
        assertThat(text(contracted, "finishedAt")).isNull();
        assertThat(text(contracted, "closedAt")).isNull();
        assertThat(text(contracted, "lastTransitionAt")).isNotNull();

        send(base + "/start", PROVIDER, "ROLE_PROFESSIONAL", null).andExpect(status().isOk());
        JsonNode started = assertConsistent(service, "IN_PROGRESS");
        assertThat(text(started, "startedAt")).isNotNull();
        assertThat(text(started, "finishedAt")).isNull();

        send(base + "/finish", PROVIDER, "ROLE_PROFESSIONAL", null).andExpect(status().isOk());
        JsonNode finished = assertConsistent(service, "TECHNICIAN_FINISHED");
        assertThat(text(finished, "startedAt")).isEqualTo(text(started, "startedAt"));
        assertThat(text(finished, "finishedAt")).isNotNull();
        assertThat(text(finished, "closedAt")).isNull();

        send("/api/services/requests/" + service.requestId() + "/closure-objection", CLIENT, "ROLE_CLIENT",
                Map.of("reason", "El grifo sigue goteando después del arreglo")).andExpect(status().isOk());
        JsonNode objected = assertConsistent(service, "IN_PROGRESS");
        // vuelve a ejecución: conserva cuándo empezó y ya no hay un fin vigente
        assertThat(text(objected, "startedAt")).isEqualTo(text(started, "startedAt"));
        assertThat(text(objected, "finishedAt")).isNull();

        send(base + "/finish", PROVIDER, "ROLE_PROFESSIONAL", null).andExpect(status().isOk());
        JsonNode refinished = assertConsistent(service, "TECHNICIAN_FINISHED");
        assertThat(text(refinished, "finishedAt")).isNotNull();

        send("/api/services/requests/" + service.requestId() + "/closure", CLIENT, "ROLE_CLIENT", null)
                .andExpect(status().isOk());
        JsonNode closed = assertConsistent(service, "CLOSED");
        assertThat(text(closed, "closedAt")).isNotNull();
        assertThat(text(closed, "finishedAt")).isEqualTo(text(refinished, "finishedAt"));
        assertThat(text(closed, "startedAt")).isEqualTo(text(started, "startedAt"));
        assertThat(text(closed, "lastTransitionAt")).isEqualTo(text(closed, "closedAt"));
    }

    @Test
    void reintentarUnaTransicionNoCambiaElHistorial() throws Exception {
        Service service = contract(CLIENT, PROVIDER, "105000");
        String start = "/api/services/contracts/" + service.contractId() + "/start";

        send(start, PROVIDER, "ROLE_PROFESSIONAL", null).andExpect(status().isOk());
        JsonNode once = assertConsistent(service, "IN_PROGRESS");
        send(start, PROVIDER, "ROLE_PROFESSIONAL", null).andExpect(status().isOk());
        JsonNode twice = assertConsistent(service, "IN_PROGRESS");

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void losServiciosSeListanDelMasRecienteAlMasAntiguoYCadaUsuarioVeSoloLosSuyos() throws Exception {
        Service first = contract(CLIENT, PROVIDER, "105000");
        Service second = contract(CLIENT, PROVIDER, "120000");
        Service foreign = contract(OTHER_CLIENT, OTHER_PROVIDER, "99000");
        send("/api/services/contracts/" + first.contractId() + "/start", PROVIDER, "ROLE_PROFESSIONAL", null)
                .andExpect(status().isOk());

        for (JsonNode page : List.of(historyOf(CLIENT, "ROLE_CLIENT"), historyOf(PROVIDER, "ROLE_PROFESSIONAL"))) {
            assertThat(page.get("items").findValuesAsText("contractId"))
                    .containsExactly(second.contractId().toString(), first.contractId().toString());
            assertThat(page.toString()).doesNotContain(foreign.contractId().toString(),
                    foreign.requestId().toString());
        }
        assertThat(historyOf(OTHER_CLIENT, "ROLE_CLIENT").get("items").findValuesAsText("contractId"))
                .containsExactly(foreign.contractId().toString());
        // actuar sobre un servicio no cambia el orden: depende de cuándo se contrató
        assertThat(text(itemOf(historyOf(CLIENT, "ROLE_CLIENT"), first), "status")).isEqualTo("IN_PROGRESS");
        assertThat(text(itemOf(historyOf(CLIENT, "ROLE_CLIENT"), second), "status")).isEqualTo("CONTRACTED");
    }

    @Test
    void elHistorialNuncaTraeLaDireccionExactaNiElMotivoDeLaObjecion() throws Exception {
        Service service = contract(CLIENT, PROVIDER, "105000");
        String base = "/api/services/contracts/" + service.contractId();
        send(base + "/start", PROVIDER, "ROLE_PROFESSIONAL", null).andExpect(status().isOk());
        send(base + "/finish", PROVIDER, "ROLE_PROFESSIONAL", null).andExpect(status().isOk());
        send("/api/services/requests/" + service.requestId() + "/closure-objection", CLIENT, "ROLE_CLIENT",
                Map.of("reason", "Motivo con datos personales de la objeción")).andExpect(status().isOk());

        for (JsonNode page : List.of(historyOf(CLIENT, "ROLE_CLIENT"), historyOf(PROVIDER, "ROLE_PROFESSIONAL"))) {
            assertThat(page.toString()).doesNotContain(EXACT_ADDRESS, "Motivo con datos personales");
        }
    }
}
