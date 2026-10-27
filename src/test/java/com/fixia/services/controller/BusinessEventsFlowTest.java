package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.OutboxStatus;
import com.fixia.services.repository.OutboxEventRepository;
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
 * Eventos de negocio del Outbox (GC-433) a lo largo del flujo real por la API: cada cambio de estado deja un evento,
 * en orden, que cumple el contrato de {@code docs/events} (GC-436); los reintentos sin cambio y las operaciones
 * rechazadas no dejan ninguno.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessEventsFlowTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID CARLOS = UUID.randomUUID();
    private static final UUID ANA = UUID.randomUUID();
    private static final String EXACT_ADDRESS = "Calle 10 # 5-20, apto 301";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TechnicianEligibilityClient eligibilityClient;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAllInBatch();
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

    private UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .get("id").asText());
    }

    private UUID publish() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "PLUMBING");
        body.put("description", "Fuga de agua debajo del lavamanos");
        body.put("approximateLocation", Map.of("city", "Bogotá", "zone", "Chapinero",
                "latitude", 4.6533, "longitude", -74.0836));
        body.put("exactLocation", Map.of("address", EXACT_ADDRESS, "latitude", 4.7110, "longitude", -74.0721));
        body.put("expectedAt", Instant.now().plus(Duration.ofDays(2)).toString());
        body.put("budgetAmount", 200000);
        body.put("budgetCurrency", "COP");
        body.put("photoKeys", List.of("requests/a.jpg", "requests/b.jpg"));
        return idOf(send("/api/services/requests", CLIENT, "ROLE_CLIENT", body).andExpect(status().isCreated()));
    }

    private ResultActions apply(UUID technician, UUID requestId, String price) throws Exception {
        return send("/api/services/applications", technician, "ROLE_PROFESSIONAL",
                Map.of("serviceRequestId", requestId, "proposedAmount", price, "currency", "COP"));
    }

    private UUID quote(UUID provider, UUID requestId, String amount) throws Exception {
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceRequestId", requestId);
        body.put("amount", amount);
        body.put("currency", "COP");
        body.put("scope", "Cambio de la tubería del lavamanos");
        body.put("conditions", "Materiales incluidos");
        body.put("agreedAt", now.plus(Duration.ofDays(3)).toString());
        body.put("validUntil", now.plus(Duration.ofDays(1)).toString());
        return idOf(send("/api/services/quotations", provider, "ROLE_PROFESSIONAL", body)
                .andExpect(status().isCreated()));
    }

    private List<OutboxEvent> history(UUID requestId) {
        return outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(requestId);
    }

    private JsonNode envelope(OutboxEvent event) throws Exception {
        return objectMapper.readTree(event.getPayload());
    }

    // ---------- Flujo completo ----------

    @Test
    void cadaCambioDeEstadoDejaUnEventoEnOrdenQueCumpleElContrato() throws Exception {
        UUID request = publish();
        UUID carlosApplication = idOf(apply(CARLOS, request, "150000").andExpect(status().isCreated()));
        UUID anaApplication = idOf(apply(ANA, request, "98500.50").andExpect(status().isCreated()));
        send("/api/services/requests/" + request + "/applications/" + anaApplication + "/selection",
                CLIENT, "ROLE_CLIENT", null).andExpect(status().isOk());
        UUID first = quote(ANA, request, "120000");
        send("/api/services/requests/" + request + "/quotations/" + first + "/rejection", CLIENT, "ROLE_CLIENT", null)
                .andExpect(status().isOk());
        UUID second = quote(ANA, request, "105000");
        send("/api/services/requests/" + request + "/quotations/" + second + "/acceptance", CLIENT, "ROLE_CLIENT",
                null).andExpect(status().isOk());

        List<OutboxEvent> events = history(request);
        assertThat(events).extracting(OutboxEvent::getEventType).containsExactly(
                "solicitud.publicada", "postulacion.registrada", "postulacion.registrada", "prestador.seleccionado",
                "cotizacion.enviada", "cotizacion.rechazada", "cotizacion.enviada", "servicio.contratado");
        assertThat(events).extracting(OutboxEvent::getAggregateSequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getAttempts()).isZero();
            assertThat(event.getTopic()).isEqualTo("fixia.services.service-requests");
            assertThat(event.getMessageKey()).isEqualTo(request.toString());
        });

        for (OutboxEvent event : events) {
            JsonNode envelope = envelope(event);
            assertThat(EventContract.violations(envelope)).as(event.getEventType()).isEmpty();
            assertThat(envelope.get("eventId").asText()).isEqualTo(event.getEventId().toString());
            assertThat(envelope.get("correlationId").asText()).isEqualTo(request.toString());
            assertThat(envelope.at("/aggregate/sequence").asLong()).isEqualTo(event.getAggregateSequence());
            assertThat(envelope.get("occurredAt").asText()).isEqualTo(event.getOccurredAt().toString());
        }

        JsonNode published = envelope(events.get(0));
        assertThat(published.at("/data/photoCount").asInt()).isEqualTo(2);
        assertThat(published.at("/actor/userId").asText()).isEqualTo(CLIENT.toString());
        JsonNode carlos = envelope(events.get(1));
        assertThat(carlos.at("/data/proposedPrice/amount").asText()).isEqualTo("150000.00");
        assertThat(carlos.at("/actor/role").asText()).isEqualTo("PROFESSIONAL");
        JsonNode selection = envelope(events.get(3));
        assertThat(selection.at("/data/selectedApplicationId").asText()).isEqualTo(anaApplication.toString());
        assertThat(selection.at("/data/selectedProposedPrice/amount").asText()).isEqualTo("98500.50");
        assertThat(selection.at("/data/rejectedApplicationIds/0").asText()).isEqualTo(carlosApplication.toString());
        JsonNode contracted = envelope(events.get(7));
        assertThat(contracted.at("/data/quotationId").asText()).isEqualTo(second.toString());
        assertThat(contracted.at("/data/price/amount").asText()).isEqualTo("105000.00");
    }

    @Test
    void iniciarElServicioDejaSuEventoYElHistorialCompletoDelFlujoReal() throws Exception {
        UUID request = publish();
        UUID application = idOf(apply(ANA, request, "98500.50").andExpect(status().isCreated()));
        send("/api/services/requests/" + request + "/applications/" + application + "/selection",
                CLIENT, "ROLE_CLIENT", null).andExpect(status().isOk());
        UUID quotation = quote(ANA, request, "105000");
        send("/api/services/requests/" + request + "/quotations/" + quotation + "/acceptance", CLIENT, "ROLE_CLIENT",
                null).andExpect(status().isOk());
        JsonNode contract = objectMapper.readTree(mockMvc.perform(
                        get("/api/services/requests/" + request + "/contract").with(as(CLIENT, "ROLE_CLIENT")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String start = "/api/services/contracts/" + contract.get("id").asText() + "/start";

        send(start, ANA, "ROLE_PROFESSIONAL", null).andExpect(status().isOk());
        send(start, ANA, "ROLE_PROFESSIONAL", null).andExpect(status().isOk());

        List<OutboxEvent> events = history(request);
        assertThat(events).extracting(OutboxEvent::getEventType).containsExactly(
                "solicitud.publicada", "postulacion.registrada", "prestador.seleccionado", "cotizacion.enviada",
                "servicio.contratado", "servicio.iniciado");
        assertThat(EventContract.violations(envelope(events.get(5)))).isEmpty();

        JsonNode timeline = objectMapper.readTree(mockMvc.perform(
                        get("/api/services/requests/" + request + "/contract").with(as(CLIENT, "ROLE_CLIENT")))
                .andReturn().getResponse().getContentAsString()).get("timeline");
        assertThat(timeline).hasSize(4);
        assertThat(timeline.findValuesAsText("toStatus"))
                .containsExactly("PUBLISHED", "PROVIDER_SELECTED", "CONTRACTED", "IN_PROGRESS");
        assertThat(timeline.get(0).get("fromStatus").isNull()).isTrue();
        assertThat(timeline.findValuesAsText("actorRole"))
                .containsExactly("CLIENT", "CLIENT", "CLIENT", "PROFESSIONAL");
    }

    @Test
    void ningunEventoLlevaLaDireccionExactaNiLasFotos() throws Exception {
        UUID request = publish();
        apply(CARLOS, request, "150000").andExpect(status().isCreated());

        assertThat(history(request)).allSatisfy(event -> assertThat(event.getPayload())
                .doesNotContain(EXACT_ADDRESS, "requests/a.jpg", "exactLocation", "photoKeys"));
    }

    @Test
    void unaCotizacionNuevaReemplazaLaAnteriorYLoDiceEnSuEvento() throws Exception {
        UUID request = publish();
        UUID application = idOf(apply(ANA, request, "98500.50").andExpect(status().isCreated()));
        send("/api/services/requests/" + request + "/applications/" + application + "/selection",
                CLIENT, "ROLE_CLIENT", null).andExpect(status().isOk());
        UUID first = quote(ANA, request, "120000");
        quote(ANA, request, "110000");

        JsonNode second = envelope(history(request).get(4));
        assertThat(second.at("/data/replacedQuotations/0/quotationId").asText()).isEqualTo(first.toString());
        assertThat(second.at("/data/replacedQuotations/0/newStatus").asText()).isEqualTo("SUPERSEDED");
        assertThat(EventContract.violations(second)).isEmpty();
    }

    // ---------- Reintentos y operaciones rechazadas ----------

    @Test
    void losReintentosSinCambioYLasOperacionesRechazadasNoDejanEventos() throws Exception {
        UUID request = publish();
        UUID application = idOf(apply(ANA, request, "98500.50").andExpect(status().isCreated()));
        String selection = "/api/services/requests/" + request + "/applications/" + application + "/selection";
        send(selection, CLIENT, "ROLE_CLIENT", null).andExpect(status().isOk());
        long afterSelection = history(request).size();
        send(selection, CLIENT, "ROLE_CLIENT", null).andExpect(status().isOk());
        assertThat(history(request)).hasSize((int) afterSelection);

        UUID quotation = quote(ANA, request, "105000");
        String acceptance = "/api/services/requests/" + request + "/quotations/" + quotation + "/acceptance";
        send(acceptance, CLIENT, "ROLE_CLIENT", null).andExpect(status().isOk());
        long before = history(request).size();

        send(acceptance, CLIENT, "ROLE_CLIENT", null).andExpect(status().isOk());
        apply(CARLOS, request, "90000").andExpect(status().isConflict());
        apply(ANA, request, "90000").andExpect(status().isConflict());
        apply(CARLOS, UUID.randomUUID(), "90000").andExpect(status().isNotFound());

        assertThat(history(request)).hasSize((int) before);
        assertThat(history(request)).extracting(OutboxEvent::getEventType).containsOnlyOnce("servicio.contratado");
        assertThat(outboxRepository.count()).isEqualTo(before);
    }

    @Test
    void seleccionarUnaPostulacionAnteriorAlPrecioPublicaElPrecioEnNulo() throws Exception {
        UUID request = publish();
        UUID application = idOf(apply(ANA, request, "98500.50").andExpect(status().isCreated()));
        jdbcTemplate.update("UPDATE applications SET proposed_amount = NULL, currency = NULL WHERE id = ?",
                application);

        send("/api/services/requests/" + request + "/applications/" + application + "/selection",
                CLIENT, "ROLE_CLIENT", null).andExpect(status().isOk());

        JsonNode selection = envelope(history(request).get(2));
        assertThat(selection.at("/data/selectedProposedPrice").isNull()).isTrue();
        assertThat(EventContract.violations(selection)).isEmpty();
    }

    @Test
    void cadaSolicitudTieneSuPropiaSecuencia() throws Exception {
        UUID first = publish();
        UUID second = publish();
        apply(CARLOS, first, "150000").andExpect(status().isCreated());
        apply(CARLOS, second, "150000").andExpect(status().isCreated());
        apply(ANA, first, "98500.50").andExpect(status().isCreated());

        assertThat(history(first)).extracting(OutboxEvent::getAggregateSequence).containsExactly(1L, 2L, 3L);
        assertThat(history(second)).extracting(OutboxEvent::getAggregateSequence).containsExactly(1L, 2L);
    }
}
