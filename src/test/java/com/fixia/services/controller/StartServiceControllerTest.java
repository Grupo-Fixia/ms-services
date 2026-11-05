package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.domain.ServiceRequestTransition;
import com.fixia.services.consumer.PaymentEvent;
import com.fixia.services.consumer.PaymentEventFixtures;
import com.fixia.services.consumer.PaymentEventHandler;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import com.fixia.services.service.EventContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inicio de la ejecución (GC-437): el prestador contratado pasa el servicio de {@code CONTRACTED} a {@code IN_PROGRESS},
 * y el cliente y el prestador consultan el mismo estado e historial. Las contrataciones se crean aceptando una cotización
 * por la API, como en producción, para que el historial y el Outbox sean los reales.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StartServiceControllerTest {

    private static final UUID CLIENT = UUID.randomUUID();
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentEventHandler paymentEvents;

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    private record Contracted(ServiceRequest request, ServiceContract contract) {
        UUID requestId() {
            return request.getId();
        }

        UUID contractId() {
            return contract.getId();
        }
    }

    /**
     * Servicio contratado con el saldo ya retenido por ms-payments (GC-490): el prestador puede iniciarlo. Las pruebas del
     * bloqueo del inicio usan {@link #contractedWithoutPayment()}.
     */
    private Contracted contracted() throws Exception {
        Contracted contracted = contractedWithoutPayment();
        ContractPayments.hold(jdbcTemplate, contracted.contractId());
        return contracted;
    }

    /** Servicio contratado de verdad: el cliente acepta la cotización por la API y nace la contratación. */
    private Contracted contractedWithoutPayment() throws Exception {
        Instant now = Instant.now();
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero", 4.6512, -74.0583),
                new ExactLocation(ADDRESS, 4.65, -74.08), now.plus(Duration.ofDays(5)), new BigDecimal("200000"),
                CurrencyCode.COP, List.of(), now);
        request.selectProvider(PROVIDER, now);
        ServiceRequest saved = serviceRequestRepository.saveAndFlush(request);
        Quotation quotation = quotationRepository.saveAndFlush(Quotation.send(saved, PROVIDER,
                new BigDecimal("150000.50"), CurrencyCode.COP, "Cambio de tubería", "Incluye materiales",
                now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)), 1, now));
        mockMvc.perform(post("/api/services/requests/" + saved.getId() + "/quotations/" + quotation.getId()
                + "/acceptance").with(client(CLIENT))).andExpect(status().isOk());
        return new Contracted(saved, contractRepository.findByServiceRequestId(saved.getId()).orElseThrow());
    }

    private static RequestPostProcessor client(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private static RequestPostProcessor professional(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id))
                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"));
    }

    private ResultActions start(UUID providerId, Object contractId) throws Exception {
        return mockMvc.perform(post("/api/services/contracts/" + contractId + "/start").with(professional(providerId)));
    }

    private ResultActions clientContract(UUID requestId) throws Exception {
        return mockMvc.perform(get("/api/services/requests/" + requestId + "/contract").with(client(CLIENT)));
    }

    private ResultActions providerContract(UUID contractId) throws Exception {
        return mockMvc.perform(get("/api/services/contracts/" + contractId).with(professional(PROVIDER)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ServiceRequestStatus statusOf(UUID requestId) {
        return serviceRequestRepository.findById(requestId).orElseThrow().getStatus();
    }

    private List<ServiceRequestTransition> timeline(UUID requestId) {
        return transitionRepository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(requestId);
    }

    private List<OutboxEvent> startedEvents(UUID requestId) {
        return outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(requestId).stream()
                .filter(event -> event.getEventType().equals("servicio.iniciado"))
                .toList();
    }

    private List<Integer> concurrently(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> pending = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                pending.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            for (Future<Integer> result : pending) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }

    // ---------- Iniciar ----------

    @Test
    void elPrestadorContratadoIniciaElServicioYQuedaEnCursoConSuHistorial() throws Exception {
        Contracted contracted = contracted();

        start(PROVIDER, contracted.contractId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(contracted.contractId().toString()))
                .andExpect(jsonPath("$.serviceStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.serviceRequest.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.timeline", hasSize(2)))
                .andExpect(jsonPath("$.timeline[0].fromStatus").value("PROVIDER_SELECTED"))
                .andExpect(jsonPath("$.timeline[0].toStatus").value("CONTRACTED"))
                .andExpect(jsonPath("$.timeline[0].actorRole").value("CLIENT"))
                .andExpect(jsonPath("$.timeline[0].actorId").value(CLIENT.toString()))
                .andExpect(jsonPath("$.timeline[1].fromStatus").value("CONTRACTED"))
                .andExpect(jsonPath("$.timeline[1].toStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.timeline[1].actorRole").value("PROFESSIONAL"))
                .andExpect(jsonPath("$.timeline[1].actorId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.timeline[1].reason").doesNotExist())
                .andExpect(jsonPath("$.timeline[1].occurredAt").exists());

        assertThat(statusOf(contracted.requestId())).isEqualTo(ServiceRequestStatus.IN_PROGRESS);
        assertThat(timeline(contracted.requestId())).hasSize(2);
    }

    @Test
    void iniciarDejaUnEventoQueCumpleElContratoYCoincideConElHistorial() throws Exception {
        Contracted contracted = contracted();

        start(PROVIDER, contracted.contractId()).andExpect(status().isOk());

        List<OutboxEvent> started = startedEvents(contracted.requestId());
        assertThat(started).hasSize(1);
        OutboxEvent event = started.get(0);
        JsonNode envelope = objectMapper.readTree(event.getPayload());
        assertThat(EventContract.violations(envelope)).isEmpty();
        // servicio.contratado es la secuencia 1 en este escenario: el inicio es la siguiente.
        assertThat(event.getAggregateSequence()).isEqualTo(2L);
        assertThat(envelope.at("/actor/userId").asText()).isEqualTo(PROVIDER.toString());
        assertThat(envelope.at("/actor/role").asText()).isEqualTo("PROFESSIONAL");
        assertThat(envelope.at("/data/contractId").asText()).isEqualTo(contracted.contractId().toString());
        assertThat(envelope.at("/data/serviceRequestId").asText()).isEqualTo(contracted.requestId().toString());
        assertThat(envelope.at("/data/clientId").asText()).isEqualTo(CLIENT.toString());
        assertThat(envelope.at("/data/providerId").asText()).isEqualTo(PROVIDER.toString());
        assertThat(envelope.at("/data/status").asText()).isEqualTo("IN_PROGRESS");

        List<ServiceRequestTransition> timeline = timeline(contracted.requestId());
        Instant startedAt = Instant.parse(envelope.at("/data/startedAt").asText());
        assertThat(timeline.get(timeline.size() - 1).getOccurredAt()).isEqualTo(startedAt);
        assertThat(event.getOccurredAt()).isEqualTo(startedAt);
    }

    @Test
    void repetirElInicioRespondeIgualSinDuplicarHistorialNiEvento() throws Exception {
        Contracted contracted = contracted();
        JsonNode first = json(start(PROVIDER, contracted.contractId()).andExpect(status().isOk()));

        JsonNode second = json(start(PROVIDER, contracted.contractId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.timeline", hasSize(2))));

        assertThat(second.get("timeline")).isEqualTo(first.get("timeline"));
        assertThat(timeline(contracted.requestId())).hasSize(2);
        assertThat(startedEvents(contracted.requestId())).hasSize(1);
        assertThat(outboxRepository.count()).isEqualTo(2);
    }

    @Test
    void dosInicioSimultaneosDejanUnaSolaTransicionYUnSoloEvento() throws Exception {
        Contracted contracted = contracted();

        List<Integer> statuses = concurrently(java.util.stream.IntStream.range(0, 8)
                .<Callable<Integer>>mapToObj(index -> () -> start(PROVIDER, contracted.contractId())
                        .andReturn().getResponse().getStatus())
                .toList());

        assertThat(statuses).containsOnly(200);
        assertThat(statusOf(contracted.requestId())).isEqualTo(ServiceRequestStatus.IN_PROGRESS);
        assertThat(timeline(contracted.requestId()))
                .filteredOn(transition -> transition.getToStatus() == ServiceRequestStatus.IN_PROGRESS)
                .hasSize(1);
        assertThat(startedEvents(contracted.requestId())).hasSize(1);
    }

    // ---------- Permisos y errores ----------

    @Test
    void unPrestadorAjenoUnaContratacionInexistenteOUnIdInvalidoRespondenIgual404() throws Exception {
        Contracted contracted = contracted();

        for (ResultActions attempt : List.of(start(OTHER_PROVIDER, contracted.contractId()),
                start(PROVIDER, UUID.randomUUID()), start(PROVIDER, "no-es-uuid"))) {
            attempt.andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Contratación no encontrada"));
        }

        assertThat(statusOf(contracted.requestId())).isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(timeline(contracted.requestId())).hasSize(1);
        assertThat(startedEvents(contracted.requestId())).isEmpty();
    }

    @Test
    void elClienteNoPuedeIniciarNiSinTokenSePuedeIniciar() throws Exception {
        Contracted contracted = contracted();

        mockMvc.perform(post("/api/services/contracts/" + contracted.contractId() + "/start").with(client(CLIENT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/services/contracts/" + contracted.contractId() + "/start"))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(contracted.requestId())).isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(startedEvents(contracted.requestId())).isEmpty();
    }

    @Test
    void desdeUnEstadoQueNoEsContratadaNiEnCursoRespondeConflictoSinCambiarNada() throws Exception {
        Contracted contracted = contracted();
        jdbcTemplate.update("UPDATE service_requests SET status = 'EXPIRED' WHERE id = ?", contracted.requestId());

        start(PROVIDER, contracted.contractId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Transición de estado no permitida"));

        assertThat(statusOf(contracted.requestId())).isEqualTo(ServiceRequestStatus.EXPIRED);
        assertThat(timeline(contracted.requestId())).hasSize(1);
        assertThat(startedEvents(contracted.requestId())).isEmpty();
    }

    // ---------- Pago retenido (GC-490) ----------

    /** Lo que hace el consumidor al leer el evento de ms-payments, sin pasar por el broker. */
    private void paymentEvent(Contracted contracted, com.fasterxml.jackson.databind.node.ObjectNode event) {
        paymentEvents.handle(PaymentEvent.parse(objectMapper, event.toString()));
    }

    private void assertNothingStarted(Contracted contracted) {
        assertThat(statusOf(contracted.requestId())).isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(timeline(contracted.requestId())).hasSize(1);
        assertThat(startedEvents(contracted.requestId())).isEmpty();
    }

    @Test
    void sinPagoConfirmadoElInicioEsConflictoConMensajeClaroYNoCambiaNada() throws Exception {
        Contracted contracted = contractedWithoutPayment();

        start(PROVIDER, contracted.contractId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Pago no retenido"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("aún no está retenido")));

        assertNothingStarted(contracted);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void conElPagoFallidoElInicioEsConflictoQueExplicaQueFaltoSaldo() throws Exception {
        Contracted contracted = contractedWithoutPayment();
        paymentEvent(contracted, PaymentEventFixtures.failed(contracted.contract(), 2));

        start(PROVIDER, contracted.contractId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Pago no retenido"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("saldo insuficiente")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("120750"))));

        assertNothingStarted(contracted);
    }

    @Test
    void cuandoElClienteRecargaYElPagoSeRetieneElPrestadorYaPuedeIniciar() throws Exception {
        Contracted contracted = contractedWithoutPayment();
        start(PROVIDER, contracted.contractId()).andExpect(status().isConflict());
        paymentEvent(contracted, PaymentEventFixtures.failed(contracted.contract(), 2));
        start(PROVIDER, contracted.contractId()).andExpect(status().isConflict());

        paymentEvent(contracted, PaymentEventFixtures.processed(contracted.contract(), 3));

        start(PROVIDER, contracted.contractId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.paymentStatus").value("HELD"));
        assertThat(startedEvents(contracted.requestId())).hasSize(1);
    }

    @Test
    void unEventoTardioONoConfirmadoNoAutorizaElInicio() throws Exception {
        Contracted contracted = contractedWithoutPayment();
        paymentEvent(contracted, PaymentEventFixtures.completed(contracted.contract(), 4));
        com.fasterxml.jackson.databind.node.ObjectNode inconsistent =
                PaymentEventFixtures.processed(contracted.contract(), 2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) inconsistent.get("data"))
                .put("clientId", UUID.randomUUID().toString());
        paymentEvent(contracted, inconsistent);

        start(PROVIDER, contracted.contractId()).andExpect(status().isConflict());

        assertNothingStarted(contracted);
    }

    @Test
    void reintentarElInicioConElPagoRetenidoSigueSiendo200SinDuplicarNada() throws Exception {
        Contracted contracted = contractedWithoutPayment();
        paymentEvent(contracted, PaymentEventFixtures.processed(contracted.contract(), 2));

        start(PROVIDER, contracted.contractId()).andExpect(status().isOk());
        // Un fallido atrasado, ya con el servicio en curso, no lo tumba ni cambia la respuesta.
        paymentEvent(contracted, PaymentEventFixtures.failed(contracted.contract(), 1));
        start(PROVIDER, contracted.contractId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("IN_PROGRESS"));

        assertThat(timeline(contracted.requestId())).hasSize(2);
        assertThat(startedEvents(contracted.requestId())).hasSize(1);
    }

    @Test
    void elPrestadorAjenoSigueViendoLaMisma404AunSinPagoRetenido() throws Exception {
        Contracted contracted = contractedWithoutPayment();

        start(OTHER_PROVIDER, contracted.contractId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Contratación no encontrada"));
    }

    @Test
    void laContratacionExponeElEstadoDelPagoAlPrestadorAlClienteYEnElListado() throws Exception {
        Contracted contracted = contractedWithoutPayment();

        for (String expected : List.of("PENDING", "FAILED", "HELD")) {
            if (expected.equals("FAILED")) {
                paymentEvent(contracted, PaymentEventFixtures.failed(contracted.contract(), 2));
            }
            if (expected.equals("HELD")) {
                paymentEvent(contracted, PaymentEventFixtures.processed(contracted.contract(), 3));
            }
            providerContract(contracted.contractId())
                    .andExpect(jsonPath("$.paymentStatus").value(expected));
            clientContract(contracted.requestId())
                    .andExpect(jsonPath("$.paymentStatus").value(expected));
            mockMvc.perform(get("/api/services/contracts").with(professional(PROVIDER)))
                    .andExpect(jsonPath("$[0].paymentStatus").value(expected));
        }
    }

    @Test
    void laRespuestaDelPagoNoTraeImportesNiDatosDeLaBilletera() throws Exception {
        Contracted contracted = contractedWithoutPayment();
        paymentEvent(contracted, PaymentEventFixtures.processed(contracted.contract(), 2));

        String body = providerContract(contracted.contractId()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("commission", "wallet", "balance", "failureReason", "120750");
    }

    // ---------- Consultar ----------

    @Test
    void laDireccionExactaSigueLlegandoAlPrestadorYAlClienteConElServicioEnCurso() throws Exception {
        Contracted contracted = contracted();
        start(PROVIDER, contracted.contractId()).andExpect(status().isOk());

        providerContract(contracted.contractId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceRequest.exactLocation.address").value(ADDRESS));
        clientContract(contracted.requestId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceRequest.exactLocation.address").value(ADDRESS));
    }

    @Test
    void clienteYPrestadorVenElMismoEstadoEHistorialYSePersistenAlRecargar() throws Exception {
        Contracted contracted = contracted();
        start(PROVIDER, contracted.contractId()).andExpect(status().isOk());

        JsonNode asProvider = json(providerContract(contracted.contractId()));
        JsonNode asClient = json(clientContract(contracted.requestId()));
        JsonNode reloaded = json(providerContract(contracted.contractId()));

        assertThat(asProvider.get("serviceStatus").asText()).isEqualTo("IN_PROGRESS");
        assertThat(asClient.get("serviceStatus")).isEqualTo(asProvider.get("serviceStatus"));
        assertThat(asClient.get("timeline")).hasSize(2).isEqualTo(asProvider.get("timeline"));
        assertThat(reloaded.get("timeline")).isEqualTo(asProvider.get("timeline"));
    }

    @Test
    void elListadoTraeElEstadoPeroNoElHistorial() throws Exception {
        Contracted contracted = contracted();
        start(PROVIDER, contracted.contractId()).andExpect(status().isOk());

        mockMvc.perform(get("/api/services/contracts").with(professional(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].serviceStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[0].timeline").doesNotExist());
    }

    @Test
    void unServicioAnteriorAlHistorialSeConsultaConLaListaVacia() throws Exception {
        Contracted contracted = contracted();
        jdbcTemplate.update("DELETE FROM service_request_transitions WHERE service_request_id = ?",
                contracted.requestId());

        providerContract(contracted.contractId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("CONTRACTED"))
                .andExpect(jsonPath("$.timeline", hasSize(0)));
    }
}
