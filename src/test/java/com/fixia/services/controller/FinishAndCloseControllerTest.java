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
import org.springframework.http.MediaType;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Finalización y cierre del servicio (GC-441): el técnico declara que terminó, el cliente confirma el cierre o lo objeta, y
 * un servicio cerrado no se reabre. Las contrataciones nacen aceptando una cotización por la API y se inician por la API,
 * para que el historial y el Outbox sean los reales.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinishAndCloseControllerTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER = UUID.randomUUID();
    private static final String ADDRESS = "Calle 10 # 5-20, apto 301";
    private static final String REASON = "El grifo sigue goteando después del arreglo";

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

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    private record Service(ServiceRequest request, ServiceContract contract) {
        UUID requestId() {
            return request.getId();
        }

        UUID contractId() {
            return contract.getId();
        }
    }

    /** Servicio contratado de verdad (el cliente acepta la cotización por la API), todavía sin iniciar. */
    private Service contracted() throws Exception {
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
        return new Service(saved, contractRepository.findByServiceRequestId(saved.getId()).orElseThrow());
    }

    private Service inProgress() throws Exception {
        Service service = contracted();
        mockMvc.perform(post("/api/services/contracts/" + service.contractId() + "/start").with(professional(PROVIDER)))
                .andExpect(status().isOk());
        return service;
    }

    private Service finished() throws Exception {
        Service service = inProgress();
        finish(PROVIDER, service.contractId()).andExpect(status().isOk());
        return service;
    }

    private Service closed() throws Exception {
        Service service = finished();
        closure(CLIENT, service.requestId()).andExpect(status().isOk());
        return service;
    }

    private static RequestPostProcessor client(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private static RequestPostProcessor professional(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id))
                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"));
    }

    private ResultActions finish(UUID providerId, Object contractId) throws Exception {
        return mockMvc.perform(post("/api/services/contracts/" + contractId + "/finish").with(professional(providerId)));
    }

    private ResultActions start(UUID providerId, Object contractId) throws Exception {
        return mockMvc.perform(post("/api/services/contracts/" + contractId + "/start").with(professional(providerId)));
    }

    private ResultActions closure(UUID clientId, Object requestId) throws Exception {
        return mockMvc.perform(post("/api/services/requests/" + requestId + "/closure").with(client(clientId)));
    }

    private ResultActions objection(UUID clientId, Object requestId, Object body) throws Exception {
        return mockMvc.perform(post("/api/services/requests/" + requestId + "/closure-objection")
                .with(client(clientId)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions objection(UUID clientId, Object requestId, String reason) throws Exception {
        return objection(clientId, requestId, Map.of("reason", reason));
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

    private List<OutboxEvent> events(UUID requestId) {
        return outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(requestId);
    }

    private List<OutboxEvent> events(UUID requestId, String type) {
        return events(requestId).stream().filter(event -> event.getEventType().equals(type)).toList();
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

    // ---------- Finalización por el técnico ----------

    @Test
    void elTecnicoDeclaraFinalizadoSinEvidenciasYQuedaEnElHistorial() throws Exception {
        Service service = inProgress();

        finish(PROVIDER, service.contractId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("TECHNICIAN_FINISHED"))
                .andExpect(jsonPath("$.serviceRequest.status").value("TECHNICIAN_FINISHED"))
                .andExpect(jsonPath("$.timeline", hasSize(3)))
                .andExpect(jsonPath("$.timeline[2].fromStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.timeline[2].toStatus").value("TECHNICIAN_FINISHED"))
                .andExpect(jsonPath("$.timeline[2].actorRole").value("PROFESSIONAL"))
                .andExpect(jsonPath("$.timeline[2].actorId").value(PROVIDER.toString()));

        assertThat(statusOf(service.requestId())).isEqualTo(ServiceRequestStatus.TECHNICIAN_FINISHED);
        List<OutboxEvent> finished = events(service.requestId(), "servicio.finalizado");
        assertThat(finished).hasSize(1);
        JsonNode envelope = objectMapper.readTree(finished.get(0).getPayload());
        assertThat(EventContract.violations(envelope)).isEmpty();
        // contratado (1), iniciado (2), finalizado (3)
        assertThat(finished.get(0).getAggregateSequence()).isEqualTo(3L);
        assertThat(envelope.at("/actor/userId").asText()).isEqualTo(PROVIDER.toString());
        assertThat(envelope.at("/actor/role").asText()).isEqualTo("PROFESSIONAL");
        assertThat(envelope.at("/data/contractId").asText()).isEqualTo(service.contractId().toString());
        assertThat(envelope.at("/data/status").asText()).isEqualTo("TECHNICIAN_FINISHED");
        assertThat(envelope.at("/data").has("evidenceCount")).isFalse();
        List<ServiceRequestTransition> timeline = timeline(service.requestId());
        assertThat(timeline.get(timeline.size() - 1).getOccurredAt())
                .isEqualTo(Instant.parse(envelope.at("/data/finishedAt").asText()));
    }

    @Test
    void repetirLaFinalizacionRespondeIgualSinDuplicarNada() throws Exception {
        Service service = inProgress();
        JsonNode first = json(finish(PROVIDER, service.contractId()).andExpect(status().isOk()));

        JsonNode second = json(finish(PROVIDER, service.contractId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("TECHNICIAN_FINISHED")));

        assertThat(second.get("timeline")).isEqualTo(first.get("timeline"));
        assertThat(timeline(service.requestId())).hasSize(3);
        assertThat(events(service.requestId(), "servicio.finalizado")).hasSize(1);
    }

    @Test
    void noSePuedeFinalizarUnServicioSinIniciarNiUnoCerrado() throws Exception {
        Service notStarted = contracted();
        finish(PROVIDER, notStarted.contractId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Transición de estado no permitida"));
        assertThat(statusOf(notStarted.requestId())).isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(events(notStarted.requestId(), "servicio.finalizado")).isEmpty();

        Service closed = closed();
        finish(PROVIDER, closed.contractId()).andExpect(status().isConflict());
        assertThat(statusOf(closed.requestId())).isEqualTo(ServiceRequestStatus.CLOSED);
    }

    @Test
    void soloElTecnicoDeLaContratacionPuedeFinalizar() throws Exception {
        Service service = inProgress();

        for (ResultActions attempt : List.of(finish(OTHER_PROVIDER, service.contractId()),
                finish(PROVIDER, UUID.randomUUID()), finish(PROVIDER, "no-es-uuid"))) {
            attempt.andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Contratación no encontrada"));
        }
        mockMvc.perform(post("/api/services/contracts/" + service.contractId() + "/finish").with(client(CLIENT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/services/contracts/" + service.contractId() + "/finish"))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(service.requestId())).isEqualTo(ServiceRequestStatus.IN_PROGRESS);
        assertThat(events(service.requestId(), "servicio.finalizado")).isEmpty();
    }

    // ---------- Cierre por el cliente ----------

    @Test
    void elClienteConfirmaElCierreYQuedaDefinitivoConSuEvento() throws Exception {
        Service service = finished();

        closure(CLIENT, service.requestId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("CLOSED"))
                .andExpect(jsonPath("$.timeline", hasSize(4)))
                .andExpect(jsonPath("$.timeline[3].fromStatus").value("TECHNICIAN_FINISHED"))
                .andExpect(jsonPath("$.timeline[3].toStatus").value("CLOSED"))
                .andExpect(jsonPath("$.timeline[3].actorRole").value("CLIENT"))
                .andExpect(jsonPath("$.timeline[3].actorId").value(CLIENT.toString()));

        assertThat(statusOf(service.requestId())).isEqualTo(ServiceRequestStatus.CLOSED);
        List<OutboxEvent> closed = events(service.requestId(), "servicio.cerrado");
        assertThat(closed).hasSize(1);
        JsonNode envelope = objectMapper.readTree(closed.get(0).getPayload());
        assertThat(EventContract.violations(envelope)).isEmpty();
        assertThat(closed.get(0).getAggregateSequence()).isEqualTo(4L);
        assertThat(envelope.at("/actor/userId").asText()).isEqualTo(CLIENT.toString());
        assertThat(envelope.at("/actor/role").asText()).isEqualTo("CLIENT");
        assertThat(envelope.at("/data/status").asText()).isEqualTo("CLOSED");
        List<ServiceRequestTransition> timeline = timeline(service.requestId());
        assertThat(timeline.get(timeline.size() - 1).getOccurredAt())
                .isEqualTo(Instant.parse(envelope.at("/data/closedAt").asText()));
    }

    @Test
    void confirmarDosVecesNoCreaDosCierres() throws Exception {
        Service service = finished();
        JsonNode first = json(closure(CLIENT, service.requestId()).andExpect(status().isOk()));

        JsonNode second = json(closure(CLIENT, service.requestId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("CLOSED")));

        assertThat(second.get("timeline")).isEqualTo(first.get("timeline"));
        assertThat(timeline(service.requestId())).hasSize(4);
        assertThat(events(service.requestId(), "servicio.cerrado")).hasSize(1);
    }

    @Test
    void noSePuedeCerrarSinQueElTecnicoLoDeclareFinalizado() throws Exception {
        Service inProgress = inProgress();
        closure(CLIENT, inProgress.requestId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Transición de estado no permitida"));
        assertThat(statusOf(inProgress.requestId())).isEqualTo(ServiceRequestStatus.IN_PROGRESS);

        Service notStarted = contracted();
        closure(CLIENT, notStarted.requestId()).andExpect(status().isConflict());
        assertThat(statusOf(notStarted.requestId())).isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(events(inProgress.requestId(), "servicio.cerrado")).isEmpty();
    }

    @Test
    void soloElClienteDuenoPuedeCerrarOObjetar() throws Exception {
        Service service = finished();

        for (ResultActions attempt : List.of(closure(OTHER_CLIENT, service.requestId()),
                closure(CLIENT, UUID.randomUUID()), closure(CLIENT, "no-es-uuid"),
                objection(OTHER_CLIENT, service.requestId(), REASON))) {
            attempt.andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Solicitud no encontrada"));
        }
        mockMvc.perform(post("/api/services/requests/" + service.requestId() + "/closure").with(professional(PROVIDER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/services/requests/" + service.requestId() + "/closure-objection")
                        .with(professional(PROVIDER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/services/requests/" + service.requestId() + "/closure"))
                .andExpect(status().isUnauthorized());

        assertThat(statusOf(service.requestId())).isEqualTo(ServiceRequestStatus.TECHNICIAN_FINISHED);
    }

    // ---------- Objeción del cliente ----------

    @Test
    void laObjecionVuelveAEnCursoConElMotivoEnElHistorialPeroNoEnElEvento() throws Exception {
        Service service = finished();

        objection(CLIENT, service.requestId(), REASON)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.timeline", hasSize(4)))
                .andExpect(jsonPath("$.timeline[3].fromStatus").value("TECHNICIAN_FINISHED"))
                .andExpect(jsonPath("$.timeline[3].toStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.timeline[3].actorRole").value("CLIENT"))
                .andExpect(jsonPath("$.timeline[3].reason").value(REASON));

        // El técnico ve la misma objeción en su historial.
        providerContract(service.contractId())
                .andExpect(jsonPath("$.serviceStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.timeline[3].reason").value(REASON));
        assertThat(statusOf(service.requestId())).isEqualTo(ServiceRequestStatus.IN_PROGRESS);
        // No es un cierre satisfactorio: no hay evento de cierre y el evento de la objeción no trae el texto.
        assertThat(events(service.requestId(), "servicio.cerrado")).isEmpty();
        List<OutboxEvent> objected = events(service.requestId(), "servicio.cierre_objetado");
        assertThat(objected).hasSize(1);
        assertThat(objected.get(0).getPayload()).doesNotContain(REASON);
        JsonNode envelope = objectMapper.readTree(objected.get(0).getPayload());
        assertThat(EventContract.violations(envelope)).isEmpty();
        assertThat(envelope.at("/actor/role").asText()).isEqualTo("CLIENT");
        assertThat(envelope.at("/data/status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void laObjecionExigeUnMotivoDeHastaQuinientosCaracteres() throws Exception {
        Service service = finished();

        for (Object body : List.of(Map.of("reason", "   "), Map.of("reason", "x".repeat(501)), Map.of())) {
            objection(CLIENT, service.requestId(), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", hasItem("reason")));
        }
        objection(CLIENT, service.requestId(), "x".repeat(500)).andExpect(status().isOk());
    }

    @Test
    void repetirLaMismaObjecionNoDuplicaPeroUnaDistintaEsConflicto() throws Exception {
        Service service = finished();
        JsonNode first = json(objection(CLIENT, service.requestId(), REASON).andExpect(status().isOk()));

        JsonNode retry = json(objection(CLIENT, service.requestId(), "  " + REASON + " ")
                .andExpect(status().isOk()).andExpect(jsonPath("$.serviceStatus").value("IN_PROGRESS")));

        assertThat(retry.get("timeline")).isEqualTo(first.get("timeline"));
        assertThat(timeline(service.requestId())).hasSize(4);
        assertThat(events(service.requestId(), "servicio.cierre_objetado")).hasSize(1);
        objection(CLIENT, service.requestId(), "Otro motivo distinto").andExpect(status().isConflict());
        assertThat(events(service.requestId(), "servicio.cierre_objetado")).hasSize(1);
    }

    @Test
    void trasObjetarElTecnicoPuedeFinalizarOtraVezYElClienteCerrar() throws Exception {
        Service service = finished();
        objection(CLIENT, service.requestId(), REASON).andExpect(status().isOk());
        // Iniciar de nuevo no hace falta: el servicio sigue en curso, y pedirlo otra vez responde igual.
        start(PROVIDER, service.contractId()).andExpect(status().isOk());

        finish(PROVIDER, service.contractId()).andExpect(status().isOk());
        closure(CLIENT, service.requestId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceStatus").value("CLOSED"));

        assertThat(events(service.requestId())).extracting(OutboxEvent::getEventType).containsExactly(
                "servicio.contratado", "servicio.iniciado", "servicio.finalizado", "servicio.cierre_objetado",
                "servicio.finalizado", "servicio.cerrado");
        assertThat(timeline(service.requestId())).extracting(ServiceRequestTransition::getToStatus).containsExactly(
                ServiceRequestStatus.CONTRACTED, ServiceRequestStatus.IN_PROGRESS,
                ServiceRequestStatus.TECHNICIAN_FINISHED, ServiceRequestStatus.IN_PROGRESS,
                ServiceRequestStatus.TECHNICIAN_FINISHED, ServiceRequestStatus.CLOSED);
    }

    @Test
    void noSePuedeObjetarUnServicioQueNoEstaFinalizado() throws Exception {
        Service inProgress = inProgress();
        objection(CLIENT, inProgress.requestId(), REASON).andExpect(status().isConflict());
        assertThat(statusOf(inProgress.requestId())).isEqualTo(ServiceRequestStatus.IN_PROGRESS);
        assertThat(events(inProgress.requestId(), "servicio.cierre_objetado")).isEmpty();
    }

    // ---------- Un servicio cerrado no se reabre ----------

    @Test
    void unaPeticionTardiaNoReabreNiRegresaUnServicioCerrado() throws Exception {
        Service service = closed();
        int events = events(service.requestId()).size();
        int transitions = timeline(service.requestId()).size();

        objection(CLIENT, service.requestId(), REASON).andExpect(status().isConflict());
        finish(PROVIDER, service.contractId()).andExpect(status().isConflict());
        start(PROVIDER, service.contractId()).andExpect(status().isConflict());

        assertThat(statusOf(service.requestId())).isEqualTo(ServiceRequestStatus.CLOSED);
        assertThat(events(service.requestId())).hasSize(events);
        assertThat(timeline(service.requestId())).hasSize(transitions);
    }

    @Test
    void laDireccionExactaSigueLlegandoALosDosHastaElCierre() throws Exception {
        Service service = finished();
        providerContract(service.contractId()).andExpect(jsonPath("$.serviceRequest.exactLocation.address").value(ADDRESS));
        clientContract(service.requestId()).andExpect(jsonPath("$.serviceRequest.exactLocation.address").value(ADDRESS));

        closure(CLIENT, service.requestId()).andExpect(status().isOk());

        providerContract(service.contractId()).andExpect(jsonPath("$.serviceRequest.exactLocation.address").value(ADDRESS));
        clientContract(service.requestId()).andExpect(jsonPath("$.serviceRequest.exactLocation.address").value(ADDRESS));
    }

    @Test
    void clienteYTecnicoVenElMismoEstadoEHistorialDelCierre() throws Exception {
        Service service = closed();

        JsonNode asClient = json(clientContract(service.requestId()));
        JsonNode asProvider = json(providerContract(service.contractId()));

        assertThat(asClient.get("serviceStatus").asText()).isEqualTo("CLOSED");
        assertThat(asClient.get("timeline")).hasSize(4).isEqualTo(asProvider.get("timeline"));
    }

    // ---------- Concurrencia ----------

    @Test
    void variosCierresSimultaneosDejanUnaSolaTransicionYUnSoloEvento() throws Exception {
        Service service = finished();

        List<Integer> statuses = concurrently(IntStream.range(0, 8)
                .<Callable<Integer>>mapToObj(index -> () -> closure(CLIENT, service.requestId())
                        .andReturn().getResponse().getStatus())
                .toList());

        assertThat(statuses).containsOnly(200);
        assertThat(statusOf(service.requestId())).isEqualTo(ServiceRequestStatus.CLOSED);
        assertThat(timeline(service.requestId()))
                .filteredOn(transition -> transition.getToStatus() == ServiceRequestStatus.CLOSED).hasSize(1);
        assertThat(events(service.requestId(), "servicio.cerrado")).hasSize(1);
    }

    @Test
    void variasFinalizacionesSimultaneasDejanUnaSolaTransicionYUnSoloEvento() throws Exception {
        Service service = inProgress();

        List<Integer> statuses = concurrently(IntStream.range(0, 8)
                .<Callable<Integer>>mapToObj(index -> () -> finish(PROVIDER, service.contractId())
                        .andReturn().getResponse().getStatus())
                .toList());

        assertThat(statuses).containsOnly(200);
        assertThat(timeline(service.requestId()))
                .filteredOn(transition -> transition.getToStatus() == ServiceRequestStatus.TECHNICIAN_FINISHED)
                .hasSize(1);
        assertThat(events(service.requestId(), "servicio.finalizado")).hasSize(1);
    }

    @Test
    void cerrarYObjetarALaVezDejanSoloUnaDecisionCoherenteConSuEvento() throws Exception {
        Service service = finished();

        List<Integer> statuses = concurrently(List.of(
                () -> closure(CLIENT, service.requestId()).andReturn().getResponse().getStatus(),
                () -> objection(CLIENT, service.requestId(), REASON).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        ServiceRequestStatus finalStatus = statusOf(service.requestId());
        if (finalStatus == ServiceRequestStatus.CLOSED) {
            assertThat(events(service.requestId(), "servicio.cerrado")).hasSize(1);
            assertThat(events(service.requestId(), "servicio.cierre_objetado")).isEmpty();
        } else {
            assertThat(finalStatus).isEqualTo(ServiceRequestStatus.IN_PROGRESS);
            assertThat(events(service.requestId(), "servicio.cierre_objetado")).hasSize(1);
            assertThat(events(service.requestId(), "servicio.cerrado")).isEmpty();
        }
    }
}
