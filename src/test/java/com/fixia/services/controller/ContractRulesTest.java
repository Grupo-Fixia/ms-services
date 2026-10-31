package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.client.TechnicianProfileClient;
import com.fixia.services.domain.OutboxEvent;
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
 * Reglas, idempotencia y contrato persistido de la contratación (GC-430), comprobados por la API real de punta a punta:
 * aceptar una cotización crea una sola contratación con lo acordado en la versión aceptada, repetir la aceptación no
 * cambia nada aunque el servicio ya haya avanzado, y la forma del JSON es la que consume Flutter.
 *
 * <p>Complementa a {@link ContractControllerTest} y {@link QuotationResponseControllerTest}, que siembran el estado por
 * los repositorios; aquí todo pasa por los endpoints para que el historial y el Outbox sean los reales.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractRulesTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER = UUID.randomUUID();
    private static final String ADDRESS = "Calle 10 # 5-20, apto 301";

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

    private UUID idOf(JsonNode node) {
        return UUID.fromString(node.get("id").asText());
    }

    /** Solicitud publicada por el cliente (todavía sin postulaciones). */
    private UUID publish() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "PLUMBING");
        body.put("description", "Fuga de agua debajo del lavamanos");
        body.put("approximateLocation", Map.of("city", "Bogotá", "zone", "Chapinero",
                "latitude", 4.6533, "longitude", -74.0836));
        body.put("exactLocation", Map.of("address", ADDRESS, "latitude", 4.7110, "longitude", -74.0721));
        body.put("expectedAt", Instant.now().plus(Duration.ofDays(2)).toString());
        body.put("budgetAmount", 200000);
        body.put("budgetCurrency", "COP");
        return idOf(json(send("/api/services/requests", CLIENT, "ROLE_CLIENT", body).andExpect(status().isCreated())));
    }

    /** Publica, postula al prestador y lo selecciona: la solicitud queda lista para cotizar. */
    private UUID selected() throws Exception {
        UUID request = publish();
        UUID application = idOf(json(send("/api/services/applications", PROVIDER, "ROLE_PROFESSIONAL",
                Map.of("serviceRequestId", request, "proposedAmount", "98500.50", "currency", "COP"))
                .andExpect(status().isCreated())));
        send("/api/services/requests/" + request + "/applications/" + application + "/selection", CLIENT,
                "ROLE_CLIENT", null).andExpect(status().isOk());
        return request;
    }

    private ResultActions quote(UUID provider, UUID request, String amount, String scope) throws Exception {
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceRequestId", request);
        body.put("amount", amount);
        body.put("currency", "COP");
        body.put("scope", scope);
        body.put("conditions", "Condiciones de " + scope);
        body.put("agreedAt", now.plus(Duration.ofDays(3)).toString());
        body.put("validUntil", now.plus(Duration.ofDays(1)).toString());
        return send("/api/services/quotations", provider, "ROLE_PROFESSIONAL", body);
    }

    private UUID sent(UUID request, String amount, String scope) throws Exception {
        return idOf(json(quote(PROVIDER, request, amount, scope).andExpect(status().isCreated())));
    }

    private ResultActions decide(UUID clientId, UUID request, UUID quotation, String decision) throws Exception {
        return send("/api/services/requests/" + request + "/quotations/" + quotation + "/" + decision, clientId,
                "ROLE_CLIENT", null);
    }

    private ResultActions accept(UUID request, UUID quotation) throws Exception {
        return decide(CLIENT, request, quotation, "acceptance");
    }

    private ResultActions reject(UUID request, UUID quotation) throws Exception {
        return decide(CLIENT, request, quotation, "rejection");
    }

    /** Servicio contratado de verdad: publicado, postulado, seleccionado, cotizado y aceptado por la API. */
    private record Contracted(UUID request, UUID quotation, UUID contract) {
    }

    private Contracted contracted() throws Exception {
        UUID request = selected();
        UUID quotation = sent(request, "105000", "Cambio de la tubería");
        accept(request, quotation).andExpect(status().isOk());
        UUID contract = idOf(getJson("/api/services/requests/" + request + "/contract", CLIENT, "ROLE_CLIENT"));
        return new Contracted(request, quotation, contract);
    }

    private void start(Contracted service) throws Exception {
        send("/api/services/contracts/" + service.contract() + "/start", PROVIDER, "ROLE_PROFESSIONAL", null)
                .andExpect(status().isOk());
    }

    private void finish(Contracted service) throws Exception {
        send("/api/services/contracts/" + service.contract() + "/finish", PROVIDER, "ROLE_PROFESSIONAL", null)
                .andExpect(status().isOk());
    }

    private void close(Contracted service) throws Exception {
        send("/api/services/requests/" + service.request() + "/closure", CLIENT, "ROLE_CLIENT", null)
                .andExpect(status().isOk());
    }

    /** Lo que cambia con cada operación: contratos, historial del servicio y eventos del Outbox. */
    private record Footprint(long contracts, int transitions, List<String> events) {
    }

    private Footprint footprint(UUID request) {
        List<ServiceRequestTransition> timeline =
                transitionRepository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(request);
        return new Footprint(contractRepository.count(), timeline.size(),
                outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(request).stream()
                        .map(OutboxEvent::getEventType).toList());
    }

    private ServiceRequestStatus statusOf(UUID request) {
        return serviceRequestRepository.findById(request).orElseThrow().getStatus();
    }

    private long count(List<String> events, String type) {
        return events.stream().filter(type::equals).count();
    }

    // ---------- Idempotencia: reintentar la aceptación ----------

    @Test
    void repetirLaAceptacionVariasVecesDejaUnSoloEventoYUnaSolaTransicionDeContratacion() throws Exception {
        Contracted service = contracted();
        Footprint once = footprint(service.request());

        for (int attempt = 0; attempt < 3; attempt++) {
            accept(service.request(), service.quotation()).andExpect(status().isOk());
        }

        Footprint after = footprint(service.request());
        assertThat(after).isEqualTo(once);
        assertThat(after.contracts()).isEqualTo(1);
        assertThat(count(after.events(), "servicio.contratado")).isEqualTo(1);
        assertThat(transitionRepository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(service.request()).stream()
                .filter(t -> t.getToStatus() == ServiceRequestStatus.CONTRACTED).count()).isEqualTo(1);
        assertThat(statusOf(service.request())).isEqualTo(ServiceRequestStatus.CONTRACTED);
    }

    @Test
    void reintentarLaAceptacionConElServicioEnCursoNoCambiaNada() throws Exception {
        Contracted service = contracted();
        start(service);
        Footprint before = footprint(service.request());

        JsonNode retried = json(accept(service.request(), service.quotation()).andExpect(status().isOk()));

        assertThat(retried.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(footprint(service.request())).isEqualTo(before);
        assertThat(statusOf(service.request())).isEqualTo(ServiceRequestStatus.IN_PROGRESS);
    }

    @Test
    void reintentarLaAceptacionConElServicioFinalizadoNoCambiaNada() throws Exception {
        Contracted service = contracted();
        start(service);
        finish(service);
        Footprint before = footprint(service.request());

        accept(service.request(), service.quotation()).andExpect(status().isOk());

        assertThat(footprint(service.request())).isEqualTo(before);
        assertThat(statusOf(service.request())).isEqualTo(ServiceRequestStatus.TECHNICIAN_FINISHED);
    }

    @Test
    void reintentarLaAceptacionConElServicioCerradoNoLoReabreNiLoContrataDeNuevo() throws Exception {
        Contracted service = contracted();
        start(service);
        finish(service);
        close(service);
        Footprint before = footprint(service.request());

        accept(service.request(), service.quotation()).andExpect(status().isOk());

        assertThat(footprint(service.request())).isEqualTo(before);
        assertThat(statusOf(service.request())).isEqualTo(ServiceRequestStatus.CLOSED);
        assertThat(contractRepository.findByServiceRequestId(service.request()).orElseThrow().getId())
                .isEqualTo(service.contract());
    }

    @Test
    void unaVezContratadaRechazarLaCotizacionAceptadaEsUn409SinEfectos() throws Exception {
        Contracted service = contracted();
        Footprint before = footprint(service.request());

        reject(service.request(), service.quotation()).andExpect(status().isConflict());

        assertThat(footprint(service.request())).isEqualTo(before);
        assertThat(statusOf(service.request())).isEqualTo(ServiceRequestStatus.CONTRACTED);
    }

    // ---------- La contratación corresponde a la versión realmente aceptada ----------

    @Test
    void conVariasVersionesElContratoEsExactamenteLaAceptadaYLasDemasNoSePuedenAceptar() throws Exception {
        UUID request = selected();
        UUID first = sent(request, "120000", "Primera propuesta");
        reject(request, first).andExpect(status().isOk());
        UUID second = sent(request, "110000", "Segunda propuesta");
        UUID third = sent(request, "105000", "Tercera propuesta");

        // antes de aceptar: solo la última enviada es la vigente
        List<JsonNode> versions = list(json(mockMvc.perform(get("/api/services/requests/" + request + "/quotations")
                .with(as(CLIENT, "ROLE_CLIENT"))).andExpect(status().isOk())));
        assertThat(versions).extracting(v -> v.get("revision").asInt()).containsExactly(3, 2, 1);
        assertThat(versions).extracting(v -> v.get("status").asText()).containsExactly("SENT", "SUPERSEDED", "REJECTED");
        assertThat(versions).extracting(v -> v.get("current").asBoolean()).containsExactly(true, false, false);

        accept(request, second).andExpect(status().isConflict());
        accept(request, first).andExpect(status().isConflict());
        assertThat(contractRepository.count()).isZero();
        accept(request, third).andExpect(status().isOk());

        JsonNode contract = getJson("/api/services/requests/" + request + "/contract", CLIENT, "ROLE_CLIENT");
        assertThat(contract.get("quotationId").asText()).isEqualTo(third.toString());
        assertThat(contract.get("amount").decimalValue()).isEqualByComparingTo("105000");
        assertThat(contract.get("scope").asText()).isEqualTo("Tercera propuesta");
        assertThat(contract.get("conditions").asText()).isEqualTo("Condiciones de Tercera propuesta");
        assertThat(contract.get("providerId").asText()).isEqualTo(PROVIDER.toString());
        assertThat(contract.get("clientId").asText()).isEqualTo(CLIENT.toString());
        // el contrato copia lo acordado en esa versión: coincide con la cotización aceptada que ve el cliente
        JsonNode accepted = json(mockMvc.perform(get("/api/services/quotations/" + third)
                .with(as(PROVIDER, "ROLE_PROFESSIONAL"))).andExpect(status().isOk()));
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("revision").asInt()).isEqualTo(3);
        assertThat(contract.get("agreedAt").asText()).isEqualTo(accepted.get("agreedAt").asText());

        // ya aceptada, ninguna versión es vigente y las anteriores siguen sin poder aceptarse
        assertThat(list(json(mockMvc.perform(get("/api/services/requests/" + request + "/quotations")
                .with(as(CLIENT, "ROLE_CLIENT"))).andExpect(status().isOk()))))
                .extracting(v -> v.get("current").asBoolean()).containsOnly(false);
        accept(request, first).andExpect(status().isConflict());
        accept(request, second).andExpect(status().isConflict());
        assertThat(contractRepository.count()).isEqualTo(1);
    }

    private static List<JsonNode> list(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).toList();
    }

    @Test
    void sinSeleccionVigenteNoSePuedeCotizarYNoHayNadaQueAceptar() throws Exception {
        UUID request = publish();

        quote(PROVIDER, request, "105000", "Sin selección").andExpect(status().isConflict());

        assertThat(quotationRepository.count()).isZero();
        assertThat(contractRepository.count()).isZero();
    }

    @Test
    void unPrestadorQueNoFueSeleccionadoNoPuedeCotizar() throws Exception {
        UUID request = selected();

        quote(OTHER_PROVIDER, request, "90000", "Intruso").andExpect(status().isConflict());

        assertThat(quotationRepository.count()).isZero();
    }

    @Test
    void soloElClienteDuenoResponde() throws Exception {
        UUID request = selected();
        UUID quotation = sent(request, "105000", "Cambio de la tubería");

        decide(OTHER_CLIENT, request, quotation, "acceptance").andExpect(status().isNotFound());
        decide(OTHER_CLIENT, request, quotation, "rejection").andExpect(status().isNotFound());
        send("/api/services/requests/" + request + "/quotations/" + quotation + "/acceptance", PROVIDER,
                "ROLE_PROFESSIONAL", null).andExpect(status().isForbidden());

        assertThat(contractRepository.count()).isZero();
        assertThat(statusOf(request)).isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
    }

    // ---------- Contrato HTTP que consume Flutter ----------

    @Test
    void laFormaDelContratoEsLaQueFlutterLeeParaElClienteElPrestadorYElListado() throws Exception {
        Contracted service = contracted();

        JsonNode asClient = getJson("/api/services/requests/" + service.request() + "/contract", CLIENT, "ROLE_CLIENT");
        JsonNode asProvider = getJson("/api/services/contracts/" + service.contract(), PROVIDER, "ROLE_PROFESSIONAL");
        JsonNode listed = getJson("/api/services/contracts", PROVIDER, "ROLE_PROFESSIONAL");

        assertThat(listed).hasSize(1);
        for (JsonNode contract : List.of(asClient, asProvider, listed.get(0))) {
            assertFlutterShape(contract, service);
        }
        // cliente y prestador ven el mismo contrato
        assertThat(asProvider.get("id")).isEqualTo(asClient.get("id"));
        assertThat(asProvider.get("amount")).isEqualTo(asClient.get("amount"));
        assertThat(asProvider.get("contractedAt")).isEqualTo(asClient.get("contractedAt"));
    }

    /** Los campos y tipos que lee {@code http_contract_repository.dart} de ms-frontend. */
    private void assertFlutterShape(JsonNode contract, Contracted service) {
        assertThat(contract.get("id").asText()).isEqualTo(service.contract().toString());
        assertThat(contract.get("serviceRequestId").asText()).isEqualTo(service.request().toString());
        assertThat(contract.get("quotationId").asText()).isEqualTo(service.quotation().toString());
        assertThat(UUID.fromString(contract.get("clientId").asText())).isEqualTo(CLIENT);
        assertThat(UUID.fromString(contract.get("providerId").asText())).isEqualTo(PROVIDER);
        assertThat(contract.get("amount").isNumber()).isTrue();
        assertThat(contract.get("amount").decimalValue()).isEqualByComparingTo("105000");
        assertThat(contract.get("currency").asText()).isEqualTo("COP");
        assertThat(contract.get("scope").isTextual()).isTrue();
        assertThat(contract.get("conditions").isTextual()).isTrue();
        assertThat(Instant.parse(contract.get("agreedAt").asText())).isNotNull();
        assertThat(Instant.parse(contract.get("contractedAt").asText())).isNotNull();
        assertThat(contract.get("serviceStatus").asText()).isEqualTo("CONTRACTED");

        JsonNode request = contract.get("serviceRequest");
        assertThat(request.get("category").asText()).isEqualTo("PLUMBING");
        assertThat(request.get("description").asText()).isEqualTo("Fuga de agua debajo del lavamanos");
        assertThat(request.at("/approximateLocation/city").asText()).isEqualTo("Bogotá");
        assertThat(request.at("/approximateLocation/zone").asText()).isEqualTo("Chapinero");
        assertThat(Instant.parse(request.get("expectedAt").asText())).isNotNull();
        // contratado: la dirección exacta llega al cliente y al prestador (ADR-009)
        assertThat(request.at("/exactLocation/address").asText()).isEqualTo(ADDRESS);
        // el pago ocurre fuera de Fixia y no hay datos de contacto
        assertThat(contract.toString()).doesNotContain("phone", "email", "payment", "pago", "password");
    }

    @Test
    void elContratoDeOtroPrestadorONoExistenteEsLaMisma404() throws Exception {
        Contracted service = contracted();

        mockMvc.perform(get("/api/services/contracts/" + service.contract()).with(as(OTHER_PROVIDER, "ROLE_PROFESSIONAL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/services/contracts/" + UUID.randomUUID()).with(as(PROVIDER, "ROLE_PROFESSIONAL")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/services/requests/" + service.request() + "/contract")
                .with(as(OTHER_CLIENT, "ROLE_CLIENT"))).andExpect(status().isNotFound());
    }
}
