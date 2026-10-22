package com.fixia.services.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Aceptar o rechazar una cotización (GC-358): la responde el cliente dueño de la solicitud; solo una cotización vigente
 * ({@code SENT}) se puede responder; repetir la misma respuesta es idempotente; y las respuestas simultáneas, o una
 * respuesta y un envío nuevo, no se cruzan (la solicitud se bloquea, igual que en GC-342, GC-348 y GC-354).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuotationResponseControllerTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();

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

    @BeforeEach
    void cleanDatabase() {
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    /** Solicitud del cliente con el prestador ya seleccionado (lo que deja GC-348). */
    private ServiceRequest selectedRequest(UUID clientId, UUID providerId) {
        Instant now = Instant.now();
        ServiceRequest request = ServiceRequest.publish(clientId, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero"), null, now.plus(Duration.ofDays(5)), List.of(), now);
        request.selectProvider(providerId, now);
        return serviceRequestRepository.saveAndFlush(request);
    }

    /** Cotización enviada en {@code sentAt}: vigente una hora y con el servicio acordado dos horas después. */
    private Quotation sent(ServiceRequest request, UUID providerId, Instant sentAt) {
        return quotationRepository.saveAndFlush(Quotation.send(request, providerId, new BigDecimal("90000"),
                CurrencyCode.COP, "Cambio de empaque", "Pago al terminar", sentAt.plus(Duration.ofHours(2)),
                sentAt.plus(Duration.ofHours(1)), sentAt));
    }

    private Quotation sent(ServiceRequest request) {
        return sent(request, PROVIDER, Instant.now());
    }

    private Quotation stored(UUID quotationId) {
        return quotationRepository.findById(quotationId).orElseThrow();
    }

    private static RequestPostProcessor client(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private static RequestPostProcessor professional(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id))
                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"));
    }

    private static String path(UUID requestId, UUID quotationId, String action) {
        return "/api/services/requests/" + requestId + "/quotations/" + quotationId + "/" + action;
    }

    private ResultActions accept(UUID clientId, UUID requestId, UUID quotationId) throws Exception {
        return mockMvc.perform(post(path(requestId, quotationId, "acceptance")).with(client(clientId)));
    }

    private ResultActions reject(UUID clientId, UUID requestId, UUID quotationId) throws Exception {
        return mockMvc.perform(post(path(requestId, quotationId, "rejection")).with(client(clientId)));
    }

    private static String validSendBody(UUID requestId) {
        Instant now = Instant.now();
        return """
                {"serviceRequestId":"%s","amount":120000,"currency":"COP","scope":"Cambio de tubería",
                 "conditions":"Incluye materiales","agreedAt":"%s","validUntil":"%s"}
                """.formatted(requestId, now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)));
    }

    private ResultActions send(UUID providerId, UUID requestId) throws Exception {
        return mockMvc.perform(post("/api/services/quotations").with(professional(providerId))
                .contentType(MediaType.APPLICATION_JSON).content(validSendBody(requestId)));
    }

    /** Lanza las tareas a la vez y devuelve sus códigos HTTP en el mismo orden. */
    private List<Integer> concurrently(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> pending = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<Integer> result : pending) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }

    // ---------- Aceptar ----------

    @Test
    void elClienteAceptaUnaCotizacionVigenteYQuedaRegistradoCuando() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);

        accept(CLIENT, request.getId(), quotation.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(quotation.getId().toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.statusName").value("Aceptada"))
                .andExpect(jsonPath("$.expired").value(false))
                .andExpect(jsonPath("$.respondedAt").isNotEmpty());

        Quotation saved = stored(quotation.getId());
        assertThat(saved.getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
        assertThat(saved.getRespondedAt()).isNotNull();
    }

    @Test
    void aceptarContrataLaSolicitudYGuardaLoAcordado() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);

        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());

        // GC-359: aceptar formaliza la contratación en la misma transacción.
        ServiceRequest saved = serviceRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(saved.getSelectedProviderId()).isEqualTo(PROVIDER);
        assertThat(contractRepository.findByServiceRequestId(request.getId())).isPresent();
    }

    @Test
    void repetirLaAceptacionDevuelveElMismoEstadoSinCambiarNada() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);
        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());
        Quotation first = stored(quotation.getId());

        accept(CLIENT, request.getId(), quotation.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        Quotation second = stored(quotation.getId());
        assertThat(second.getRespondedAt()).isEqualTo(first.getRespondedAt());
        assertThat(second.getUpdatedAt()).isEqualTo(first.getUpdatedAt());
        assertThat(second.getVersion()).isEqualTo(first.getVersion());
    }

    @Test
    void unaCotizacionRechazadaNoSePuedeAceptar() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);
        reject(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());

        accept(CLIENT, request.getId(), quotation.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cambio de estado no permitido"));
        assertThat(stored(quotation.getId()).getStatus()).isEqualTo(QuotationStatus.REJECTED);
    }

    @Test
    void unaCotizacionReemplazadaPorOtraNuevaNoSePuedeAceptar() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation old = sent(request);
        send(PROVIDER, request.getId()).andExpect(status().isCreated());
        assertThat(stored(old.getId()).getStatus()).isEqualTo(QuotationStatus.SUPERSEDED);

        accept(CLIENT, request.getId(), old.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cambio de estado no permitido"));
        assertThat(stored(old.getId()).getStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
    }

    @Test
    void unaCotizacionVencidaNoSePuedeAceptarYSigueEnviada() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation expired = sent(request, PROVIDER, Instant.now().minus(Duration.ofDays(1)));

        accept(CLIENT, request.getId(), expired.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La cotización está vencida y no se puede aceptar"));

        assertThat(stored(expired.getId()).getStatus()).isEqualTo(QuotationStatus.SENT);
        mockMvc.perform(get("/api/services/requests/" + request.getId() + "/quotations").with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SENT"))
                .andExpect(jsonPath("$[0].expired").value(true));
    }

    @Test
    void siYaHayOtraAceptadaNoSeAceptaUnaSegunda() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        // Dato anterior a GC-359: una aceptada que no llegó a contratarse (la solicitud sigue con su prestador).
        Quotation accepted = sent(request);
        accepted.accept(Instant.now());
        quotationRepository.saveAndFlush(accepted);
        // Caso defensivo: aunque el envío ya lo impide, una segunda enviada no debe poder aceptarse.
        Quotation another = quotationRepository.saveAndFlush(Quotation.send(request, PROVIDER, new BigDecimal("5000"),
                CurrencyCode.COP, "Otra", "Otra", Instant.now().plus(Duration.ofHours(3)),
                Instant.now().plus(Duration.ofHours(2)), Instant.now()));

        accept(CLIENT, request.getId(), another.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La solicitud ya tiene una cotización aceptada"));
        assertThat(stored(another.getId()).getStatus()).isEqualTo(QuotationStatus.SENT);
    }

    // ---------- Rechazar ----------

    @Test
    void elClienteRechazaUnaCotizacionVigenteYElPrestadorPuedeEnviarOtra() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);

        reject(CLIENT, request.getId(), quotation.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusName").value("Rechazada"))
                .andExpect(jsonPath("$.respondedAt").isNotEmpty());

        assertThat(serviceRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
        send(PROVIDER, request.getId()).andExpect(status().isCreated());
        assertThat(stored(quotation.getId()).getStatus()).isEqualTo(QuotationStatus.REJECTED);
    }

    @Test
    void repetirElRechazoDevuelveElMismoEstadoSinCambiarNada() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);
        reject(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());
        Quotation first = stored(quotation.getId());

        reject(CLIENT, request.getId(), quotation.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertThat(stored(quotation.getId()).getUpdatedAt()).isEqualTo(first.getUpdatedAt());
    }

    @Test
    void unaCotizacionAceptadaNoSePuedeRechazar() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);
        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());

        reject(CLIENT, request.getId(), quotation.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cambio de estado no permitido"));
        assertThat(stored(quotation.getId()).getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
    }

    @Test
    void unaCotizacionVencidaTampocoSePuedeRechazar() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation expired = sent(request, PROVIDER, Instant.now().minus(Duration.ofDays(1)));

        reject(CLIENT, request.getId(), expired.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La cotización está vencida y no se puede rechazar"));
        assertThat(stored(expired.getId()).getStatus()).isEqualTo(QuotationStatus.SENT);
    }

    // ---------- Estado de la solicitud ----------

    @Test
    void sinPrestadorSeleccionadoVigenteLaSolicitudNoAdmiteResponder() throws Exception {
        ServiceRequest cancelled = selectedRequest(CLIENT, PROVIDER);
        Quotation sentOne = sent(cancelled);
        cancelled.cancel(Instant.now());
        serviceRequestRepository.saveAndFlush(cancelled);
        ServiceRequest contracted = selectedRequest(CLIENT, PROVIDER);
        Quotation sentTwo = sent(contracted);
        contracted.contract(Instant.now());
        serviceRequestRepository.saveAndFlush(contracted);

        accept(CLIENT, cancelled.getId(), sentOne.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La solicitud ya no admite responder cotizaciones"));
        reject(CLIENT, contracted.getId(), sentTwo.getId()).andExpect(status().isConflict());
        assertThat(stored(sentOne.getId()).getStatus()).isEqualTo(QuotationStatus.SENT);
        assertThat(stored(sentTwo.getId()).getStatus()).isEqualTo(QuotationStatus.SENT);
    }

    @Test
    void reintentarLaAceptacionTrasContratarLaSolicitudSigueSiendoIdempotente() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);
        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());
        assertThat(serviceRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.CONTRACTED);

        accept(CLIENT, request.getId(), quotation.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(contractRepository.count()).isEqualTo(1);
    }

    // ---------- Quién puede responder y sobre qué ----------

    @Test
    void otroClienteRecibeLaMisma404QueSiLaSolicitudNoExistiera() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);

        String foreign = accept(OTHER_CLIENT, request.getId(), quotation.getId())
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing = accept(OTHER_CLIENT, UUID.randomUUID(), quotation.getId())
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(foreign).get("detail")).isEqualTo(objectMapper.readTree(missing).get("detail"));
        reject(OTHER_CLIENT, request.getId(), quotation.getId()).andExpect(status().isNotFound());
        assertThat(stored(quotation.getId()).getStatus()).isEqualTo(QuotationStatus.SENT);
    }

    @Test
    void unaCotizacionDeOtraSolicitudNoSeEncuentraAunqueLaSolicitudSeaDelCliente() throws Exception {
        ServiceRequest first = selectedRequest(CLIENT, PROVIDER);
        ServiceRequest second = selectedRequest(CLIENT, PROVIDER);
        Quotation ofSecond = sent(second);

        accept(CLIENT, first.getId(), ofSecond.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cotización no encontrada"));
        assertThat(stored(ofSecond.getId()).getStatus()).isEqualTo(QuotationStatus.SENT);
    }

    @Test
    void unaCotizacionInexistenteOUnIdentificadorInvalidoResponden404() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);

        accept(CLIENT, request.getId(), UUID.randomUUID()).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/services/requests/" + request.getId() + "/quotations/no-es-uuid/acceptance")
                .with(client(CLIENT))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/services/requests/no-es-uuid/quotations/" + UUID.randomUUID() + "/rejection")
                .with(client(CLIENT))).andExpect(status().isNotFound());
    }

    @Test
    void elPrestadorNoPuedeAceptarNiRechazarSusPropiasCotizaciones() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);

        mockMvc.perform(post(path(request.getId(), quotation.getId(), "acceptance")).with(professional(PROVIDER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path(request.getId(), quotation.getId(), "rejection")).with(professional(PROVIDER)))
                .andExpect(status().isForbidden());
        assertThat(stored(quotation.getId()).getStatus()).isEqualTo(QuotationStatus.SENT);
    }

    @Test
    void sinTokenResponde401() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);

        mockMvc.perform(post(path(request.getId(), quotation.getId(), "acceptance"))).andExpect(status().isUnauthorized());
        mockMvc.perform(post(path(request.getId(), quotation.getId(), "rejection"))).andExpect(status().isUnauthorized());
    }

    @Test
    void unSubjectConFormatoInvalidoResponde401() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);

        mockMvc.perform(post(path(request.getId(), quotation.getId(), "acceptance"))
                        .with(jwt().jwt(token -> token.subject("cliente-sin-prefijo"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"))))
                .andExpect(status().isUnauthorized());
    }

    // ---------- Concurrencia ----------

    @Test
    void variasAceptacionesSimultaneasDejanUnaSolaAceptadaYTodasResponden200() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> accept(CLIENT, request.getId(), quotation.getId()).andReturn().getResponse().getStatus());
        }

        assertThat(concurrently(tasks)).containsOnly(200);
        assertThat(stored(quotation.getId()).getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
    }

    @Test
    void aceptarYRechazarAlMismoTiempoDejanUnSoloResultadoCoherente() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation quotation = sent(request);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> accept(CLIENT, request.getId(), quotation.getId()).andReturn().getResponse().getStatus());
            tasks.add(() -> reject(CLIENT, request.getId(), quotation.getId()).andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = concurrently(tasks);

        QuotationStatus winner = stored(quotation.getId()).getStatus();
        assertThat(winner).isIn(QuotationStatus.ACCEPTED, QuotationStatus.REJECTED);
        for (int i = 0; i < statuses.size(); i++) {
            boolean wasAccept = i % 2 == 0;
            boolean sameAsWinner = wasAccept == (winner == QuotationStatus.ACCEPTED);
            assertThat(statuses.get(i)).as("operación %d", i).isEqualTo(sameAsWinner ? 200 : 409);
        }
    }

    @Test
    void aceptarYEnviarUnaNuevaAlMismoTiempoNoDejanUnaAceptadaJuntoAOtraEnviada() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation old = sent(request);
        List<Callable<Integer>> tasks = List.of(
                () -> accept(CLIENT, request.getId(), old.getId()).andReturn().getResponse().getStatus(),
                () -> send(PROVIDER, request.getId()).andReturn().getResponse().getStatus());

        List<Integer> statuses = concurrently(tasks);

        List<Quotation> all = quotationRepository.findByServiceRequestIdOrderByCreatedAtDesc(request.getId());
        long accepted = all.stream().filter(q -> q.getStatus() == QuotationStatus.ACCEPTED).count();
        long waiting = all.stream().filter(q -> q.getStatus() == QuotationStatus.SENT).count();
        if (accepted == 1) {
            // Ganó la aceptación: el envío se rechazó y no hay ninguna esperando respuesta.
            assertThat(statuses).containsExactly(200, 409);
            assertThat(waiting).isZero();
        } else {
            // Ganó el envío: la vieja quedó reemplazada, la aceptación falló y la nueva espera respuesta.
            assertThat(statuses).containsExactly(409, 201);
            assertThat(stored(old.getId()).getStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
            assertThat(waiting).isEqualTo(1);
        }
    }
}
