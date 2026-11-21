package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceMessageRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chat real por servicio contratado (GC-527, {@code docs/contracts/mensajeria.md}): solo el cliente y el técnico que
 * ejecuta leen y escriben, los mensajes persisten (otra sesión los ve), se aíslan por contratación y, cerrado el
 * servicio, la conversación solo se lee.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceMessageControllerTest {

    private final UUID client = UUID.randomUUID();
    private final UUID technician = UUID.randomUUID();
    private final UUID otherClient = UUID.randomUUID();
    private final UUID otherTechnician = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();

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
    private ServiceMessageRepository messageRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @BeforeEach
    void clean() {
        messageRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    /** Una contratación: {@code provider} es el prestador y {@code executor} quien cotizó y ejecuta. */
    private ServiceContract contracted(UUID clientId, UUID provider, UUID executor) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ServiceRequest request = ServiceRequest.publish(clientId, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero", 4.6512, -74.0583),
                new ExactLocation("Calle 1 # 2-3", 4.65, -74.08), now.plus(Duration.ofDays(5)),
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), now);
        request.selectProvider(provider, now);
        ServiceRequest saved = requestRepository.saveAndFlush(request);
        Quotation quotation = Quotation.send(saved, provider, executor, new BigDecimal("150000"), CurrencyCode.COP,
                "Cambio de tubería", "Incluye materiales", now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)),
                1, now);
        quotation.accept(now);
        quotationRepository.saveAndFlush(quotation);
        saved.contract(now);
        requestRepository.saveAndFlush(saved);
        return contractRepository.saveAndFlush(ServiceContract.formalize(saved, quotation, now));
    }

    private ServiceContract contracted() {
        return contracted(client, technician, technician);
    }

    private void closeService(ServiceContract contract) {
        ServiceRequest request = requestRepository.findById(contract.getServiceRequestId()).orElseThrow();
        Instant now = Instant.now();
        request.start(now);
        request.finish(now);
        request.close(now);
        requestRepository.saveAndFlush(request);
    }

    private static RequestPostProcessor as(UUID id, String role) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority(role));
    }

    private static String byTechnician(ServiceContract contract) {
        return "/api/services/contracts/" + contract.getId() + "/messages";
    }

    private static String byClient(ServiceContract contract) {
        return "/api/services/requests/" + contract.getServiceRequestId() + "/messages";
    }

    private ResultActions send(String path, RequestPostProcessor user, String body, UUID clientMessageId)
            throws Exception {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("body", body);
        if (clientMessageId != null) {
            json.put("clientMessageId", clientMessageId);
        }
        return mockMvc.perform(post(path).with(user).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(json)));
    }

    /** La lectura es asíncrona (long polling, aunque responda al instante): se completa con {@code asyncDispatch}. */
    private ResultActions readRaw(String path, RequestPostProcessor user, String query) throws Exception {
        MvcResult started = mockMvc.perform(get(path + query).with(user)).andReturn();
        return started.getRequest().isAsyncStarted()
                ? mockMvc.perform(asyncDispatch(started))
                : mockMvc.perform(get(path + query).with(user));
    }

    private JsonNode read(String path, RequestPostProcessor user, String query) throws Exception {
        return objectMapper.readTree(readRaw(path, user, query).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    // ---------- Conversación ----------

    @Test
    void clienteYTecnicoSeEscribenYAmbosVenLaMismaConversacion() throws Exception {
        ServiceContract contract = contracted();

        send(byClient(contract), as(client, "ROLE_CLIENT"), "  Hola, ¿llega a las 9?  ", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Hola, ¿llega a las 9?"))
                .andExpect(jsonPath("$.senderRole").value("CLIENT"))
                .andExpect(jsonPath("$.recipientId").value(technician.toString()))
                .andExpect(jsonPath("$.mine").value(true));
        send(byTechnician(contract), as(technician, "ROLE_PROFESSIONAL"), "Sí, a las 9 en punto", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderRole").value("PROFESSIONAL"))
                .andExpect(jsonPath("$.recipientId").value(client.toString()));

        // Otra «sesión» (otro token del mismo usuario) ve lo mismo: está persistido.
        JsonNode forClient = read(byClient(contract), as(client, "ROLE_CLIENT"), "");
        assertThat(forClient.get("items").findValuesAsText("body"))
                .containsExactly("Hola, ¿llega a las 9?", "Sí, a las 9 en punto");
        assertThat(forClient.get("items").findValuesAsText("mine")).containsExactly("true", "false");
        assertThat(forClient.get("open").asBoolean()).isTrue();
        assertThat(forClient.get("hasMore").asBoolean()).isFalse();

        JsonNode forTechnician = read(byTechnician(contract), as(technician, "ROLE_PROFESSIONAL"), "");
        assertThat(forTechnician.get("items").findValuesAsText("mine")).containsExactly("false", "true");
        assertThat(forTechnician.get("nextCursor").asText()).isEqualTo(forClient.get("nextCursor").asText());

        // Los mensajes no viajan en eventos: su texto es privado.
        assertThat(outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(contract.getServiceRequestId())
                .stream().map(e -> e.getPayload()).noneMatch(p -> p.contains("llega a las 9"))).isTrue();
    }

    @Test
    void conElCursorSoloLleganLosMensajesNuevosYSePaginaSinRepetir() throws Exception {
        ServiceContract contract = contracted();
        for (int i = 1; i <= 5; i++) {
            send(byClient(contract), as(client, "ROLE_CLIENT"), "Mensaje " + i, null).andExpect(status().isCreated());
        }

        JsonNode first = read(byClient(contract), as(client, "ROLE_CLIENT"), "?limit=2");
        assertThat(first.get("items").findValuesAsText("body")).containsExactly("Mensaje 1", "Mensaje 2");
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        JsonNode second = read(byClient(contract), as(client, "ROLE_CLIENT"),
                "?limit=2&after=" + first.get("nextCursor").asText());
        assertThat(second.get("items").findValuesAsText("body")).containsExactly("Mensaje 3", "Mensaje 4");
        JsonNode third = read(byClient(contract), as(client, "ROLE_CLIENT"),
                "?limit=2&after=" + second.get("nextCursor").asText());
        assertThat(third.get("items").findValuesAsText("body")).containsExactly("Mensaje 5");
        assertThat(third.get("hasMore").asBoolean()).isFalse();

        // Preguntar de nuevo sin novedades: lista vacía y el mismo cursor para seguir preguntando.
        String last = third.get("nextCursor").asText();
        JsonNode nothing = read(byTechnician(contract), as(technician, "ROLE_PROFESSIONAL"), "?after=" + last);
        assertThat(nothing.get("items")).isEmpty();
        assertThat(nothing.get("nextCursor").asText()).isEqualTo(last);

        send(byTechnician(contract), as(technician, "ROLE_PROFESSIONAL"), "Nuevo", null);
        assertThat(read(byClient(contract), as(client, "ROLE_CLIENT"), "?after=" + last)
                .get("items").findValuesAsText("body")).containsExactly("Nuevo");
    }

    // ---------- Long polling (wait) ----------

    @Test
    void conWaitYMensajesPendientesRespondeAlInstante() throws Exception {
        ServiceContract contract = contracted();
        send(byClient(contract), as(client, "ROLE_CLIENT"), "Ya estaba", null).andExpect(status().isCreated());

        long start = System.nanoTime();
        JsonNode page = read(byTechnician(contract), as(technician, "ROLE_PROFESSIONAL"), "?wait=25");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(page.get("items").findValuesAsText("body")).containsExactly("Ya estaba");
    }

    @Test
    void conWaitLaPeticionEsperaYRespondeApenasLlegaUnMensaje() throws Exception {
        ServiceContract contract = contracted();
        send(byClient(contract), as(client, "ROLE_CLIENT"), "Primero", null).andExpect(status().isCreated());
        String cursor = read(byClient(contract), as(client, "ROLE_CLIENT"), "").get("nextCursor").asText();

        // El técnico espera lo nuevo; el cliente escribe 1,5 s después desde otro hilo.
        MvcResult waiting = mockMvc.perform(get(byTechnician(contract) + "?wait=10&after=" + cursor)
                .with(as(technician, "ROLE_PROFESSIONAL"))).andReturn();
        assertThat(waiting.getRequest().isAsyncStarted()).isTrue();
        long start = System.nanoTime();
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(1500);
                send(byClient(contract), as(client, "ROLE_CLIENT"), "Llegó mientras esperaba", null);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        writer.start();

        String body = mockMvc.perform(asyncDispatch(waiting)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        writer.join();
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertThat(objectMapper.readTree(body).get("items").findValuesAsText("body"))
                .containsExactly("Llegó mientras esperaba");
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(1400)).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    void sinNovedadesAlCumplirseLaEsperaRespondeVacioConElMismoCursor() throws Exception {
        ServiceContract contract = contracted();
        send(byClient(contract), as(client, "ROLE_CLIENT"), "Hola", null).andExpect(status().isCreated());
        String cursor = read(byClient(contract), as(client, "ROLE_CLIENT"), "").get("nextCursor").asText();

        long start = System.nanoTime();
        JsonNode page = read(byClient(contract), as(client, "ROLE_CLIENT"), "?wait=2&after=" + cursor);
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("nextCursor").asText()).isEqualTo(cursor);
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(1900)).isLessThan(Duration.ofSeconds(6));
    }

    @Test
    void laEsperaSeValidaYLosPermisosSeRevisanAntesDeEsperar() throws Exception {
        ServiceContract contract = contracted();

        for (String wait : List.of("-1", "26")) {
            mockMvc.perform(get(byClient(contract) + "?wait=" + wait).with(as(client, "ROLE_CLIENT")))
                    .andExpect(status().isBadRequest());
        }
        // Un ajeno recibe 404 enseguida, sin quedarse esperando.
        long start = System.nanoTime();
        mockMvc.perform(get(byClient(contract) + "?wait=25").with(as(otherClient, "ROLE_CLIENT")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(byTechnician(contract) + "?wait=25").with(as(company, "ROLE_COMPANY")))
                .andExpect(status().isForbidden());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    // ---------- Aislamiento y permisos ----------

    @Test
    void nadieMasLeeNiEscribe() throws Exception {
        ServiceContract contract = contracted();
        ServiceContract other = contracted(otherClient, otherTechnician, otherTechnician);
        send(byClient(contract), as(client, "ROLE_CLIENT"), "Privado", null).andExpect(status().isCreated());

        // Otro cliente u otro técnico: 404, como si no existiera.
        mockMvc.perform(get(byClient(contract)).with(as(otherClient, "ROLE_CLIENT"))).andExpect(status().isNotFound());
        send(byClient(contract), as(otherClient, "ROLE_CLIENT"), "Hola", null).andExpect(status().isNotFound());
        mockMvc.perform(get(byTechnician(contract)).with(as(otherTechnician, "ROLE_PROFESSIONAL")))
                .andExpect(status().isNotFound());
        send(byTechnician(contract), as(otherTechnician, "ROLE_PROFESSIONAL"), "Hola", null)
                .andExpect(status().isNotFound());
        // Ni cruzando puertas: el cliente no entra por la ruta del técnico ni al revés.
        mockMvc.perform(get(byTechnician(contract)).with(as(client, "ROLE_CLIENT"))).andExpect(status().isForbidden());
        mockMvc.perform(get(byClient(contract)).with(as(technician, "ROLE_PROFESSIONAL")))
                .andExpect(status().isForbidden());
        // La conversación de otro contrato no se mezcla.
        assertThat(read(byClient(other), as(otherClient, "ROLE_CLIENT"), "").get("items")).isEmpty();
        // Sin sesión.
        mockMvc.perform(get(byClient(contract))).andExpect(status().isUnauthorized());
    }

    @Test
    void enUnServicioDeEmpresaHablanElClienteYElTecnicoYLaEmpresaNoEntra() throws Exception {
        ServiceContract contract = contracted(client, company, technician);

        send(byTechnician(contract), as(technician, "ROLE_PROFESSIONAL"), "Soy el técnico de la empresa", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipientId").value(client.toString()));
        send(byClient(contract), as(client, "ROLE_CLIENT"), "Perfecto", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipientId").value(technician.toString()));

        mockMvc.perform(get(byTechnician(contract)).with(as(company, "ROLE_COMPANY"))).andExpect(status().isForbidden());
        send(byTechnician(contract), as(company, "ROLE_COMPANY"), "Hola", null).andExpect(status().isForbidden());
        // La empresa sí consulta el contrato (GC-551), pero no los mensajes.
        mockMvc.perform(get("/api/services/contracts/" + contract.getId()).with(as(company, "ROLE_COMPANY")))
                .andExpect(status().isOk());
    }

    @Test
    void sinContratarNoHayConversacion() throws Exception {
        Instant now = Instant.now();
        ServiceRequest request = requestRepository.saveAndFlush(ServiceRequest.publish(client,
                ServiceCategory.PLUMBING, "Fuga de agua", new ApproximateLocation("Bogotá", "Chapinero", 4.65, -74.08),
                null, now.plus(Duration.ofDays(2)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now));

        mockMvc.perform(get("/api/services/requests/" + request.getId() + "/messages").with(as(client, "ROLE_CLIENT")))
                .andExpect(status().isNotFound());
    }

    // ---------- Cierre ----------

    @Test
    void cerradoElServicioLaConversacionSoloSeLee() throws Exception {
        ServiceContract contract = contracted();
        send(byClient(contract), as(client, "ROLE_CLIENT"), "Gracias", null).andExpect(status().isCreated());
        closeService(contract);

        send(byClient(contract), as(client, "ROLE_CLIENT"), "Otra cosa", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conversación cerrada"));
        send(byTechnician(contract), as(technician, "ROLE_PROFESSIONAL"), "A usted", null)
                .andExpect(status().isConflict());
        JsonNode page = read(byClient(contract), as(client, "ROLE_CLIENT"), "");
        assertThat(page.get("items").findValuesAsText("body")).containsExactly("Gracias");
        assertThat(page.get("open").asBoolean()).isFalse();
    }

    // ---------- Validación ----------

    @Test
    void mensajesYParametrosInvalidosResponden400() throws Exception {
        ServiceContract contract = contracted();

        send(byClient(contract), as(client, "ROLE_CLIENT"), "   ", null).andExpect(status().isBadRequest());
        send(byClient(contract), as(client, "ROLE_CLIENT"), "x".repeat(2001), null).andExpect(status().isBadRequest());
        send(byClient(contract), as(client, "ROLE_CLIENT"), "x".repeat(2000), null).andExpect(status().isCreated());
        mockMvc.perform(get(byClient(contract) + "?limit=0").with(as(client, "ROLE_CLIENT")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(byClient(contract) + "?limit=101").with(as(client, "ROLE_CLIENT")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(byClient(contract) + "?after=no-es-un-cursor").with(as(client, "ROLE_CLIENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El cursor no es válido"));
        assertThat(messageRepository.count()).isEqualTo(1);
    }

    // ---------- Reintentos ----------

    @Test
    void reintentarElMismoEnvioNoDuplica() throws Exception {
        ServiceContract contract = contracted();
        UUID id = UUID.randomUUID();

        String first = send(byClient(contract), as(client, "ROLE_CLIENT"), "Hola", id)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String retry = send(byClient(contract), as(client, "ROLE_CLIENT"), "Hola", id)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(retry).get("id")).isEqualTo(objectMapper.readTree(first).get("id"));
        send(byClient(contract), as(client, "ROLE_CLIENT"), "Otro texto", id).andExpect(status().isConflict());
        assertThat(messageRepository.count()).isEqualTo(1);
    }

    @Test
    void reintentosSimultaneosDejanUnSoloMensaje() throws Exception {
        ServiceContract contract = contracted();
        UUID id = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Callable<Integer> call = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return send(byClient(contract), as(client, "ROLE_CLIENT"), "Hola", id)
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(200, 201).contains(201);
            assertThat(messageRepository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
