package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contratación (GC-359): aceptar una cotización formaliza la contratación en la misma transacción, la guarda con una
 * copia de lo acordado y entrega la dirección exacta (solo entonces) al cliente y al prestador contratado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractControllerTest {

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

    @BeforeEach
    void cleanDatabase() {
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    /** Solicitud del cliente con el prestador seleccionado y la dirección exacta indicada. */
    private ServiceRequest selectedRequest(UUID clientId, UUID providerId, ExactLocation exact) {
        Instant now = Instant.now();
        ServiceRequest request = ServiceRequest.publish(clientId, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero", 4.6512, -74.0583), exact,
                now.plus(Duration.ofDays(5)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now);
        request.selectProvider(providerId, now);
        return serviceRequestRepository.saveAndFlush(request);
    }

    private ServiceRequest selectedRequest() {
        return selectedRequest(CLIENT, PROVIDER, new ExactLocation(ADDRESS, 4.65, -74.08));
    }

    private Quotation sent(ServiceRequest request) {
        Instant now = Instant.now();
        return quotationRepository.saveAndFlush(Quotation.send(request, PROVIDER, new BigDecimal("150000.50"),
                CurrencyCode.COP, "Cambio de tubería del lavamanos", "Incluye materiales; pago al terminar",
                now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)), now));
    }

    private static RequestPostProcessor client(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private static RequestPostProcessor professional(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id))
                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"));
    }

    private ResultActions accept(UUID clientId, UUID requestId, UUID quotationId) throws Exception {
        return mockMvc.perform(post("/api/services/requests/" + requestId + "/quotations/" + quotationId + "/acceptance")
                .with(client(clientId)));
    }

    private ResultActions reject(UUID clientId, UUID requestId, UUID quotationId) throws Exception {
        return mockMvc.perform(post("/api/services/requests/" + requestId + "/quotations/" + quotationId + "/rejection")
                .with(client(clientId)));
    }

    private ResultActions clientContract(UUID clientId, UUID requestId) throws Exception {
        return mockMvc.perform(get("/api/services/requests/" + requestId + "/contract").with(client(clientId)));
    }

    private ResultActions providerContract(UUID providerId, UUID contractId) throws Exception {
        return mockMvc.perform(get("/api/services/contracts/" + contractId).with(professional(providerId)));
    }

    private ResultActions send(UUID providerId, UUID requestId) throws Exception {
        Instant now = Instant.now();
        return mockMvc.perform(post("/api/services/quotations").with(professional(providerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"serviceRequestId":"%s","amount":120000,"currency":"COP","scope":"Cambio de tubería",
                         "conditions":"Incluye materiales","agreedAt":"%s","validUntil":"%s"}
                        """.formatted(requestId, now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)))));
    }

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

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---------- La contratación nace al aceptar ----------

    @Test
    void aceptarFormalizaLaContratacionConLoAcordado() throws Exception {
        ServiceRequest request = selectedRequest();
        Quotation quotation = sent(request);

        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());

        ServiceContract contract = contractRepository.findByServiceRequestId(request.getId()).orElseThrow();
        assertThat(contract.getQuotationId()).isEqualTo(quotation.getId());
        assertThat(contract.getClientId()).isEqualTo(CLIENT);
        assertThat(contract.getProviderId()).isEqualTo(PROVIDER);
        assertThat(contract.getAmount()).isEqualByComparingTo("150000.50");
        assertThat(contract.getCurrency()).isEqualTo(CurrencyCode.COP);
        assertThat(contract.getScope()).isEqualTo("Cambio de tubería del lavamanos");
        assertThat(contract.getConditions()).isEqualTo("Incluye materiales; pago al terminar");
        // la base guarda microsegundos (redondea); Instant.now() en Linux trae nanosegundos
        assertThat(Duration.between(contract.getAgreedAt(), quotation.getAgreedAt()).abs())
                .isLessThanOrEqualTo(Duration.of(1, ChronoUnit.MICROS));
        assertThat(serviceRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(quotationRepository.findById(quotation.getId()).orElseThrow().getStatus())
                .isEqualTo(QuotationStatus.ACCEPTED);
    }

    @Test
    void rechazarNoCreaContratacionNiCambiaLaSolicitud() throws Exception {
        ServiceRequest request = selectedRequest();
        Quotation quotation = sent(request);

        reject(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());

        assertThat(contractRepository.count()).isZero();
        assertThat(serviceRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
    }

    @Test
    void repetirLaAceptacionNoDuplicaNiCambiaLaContratacion() throws Exception {
        ServiceRequest request = selectedRequest();
        Quotation quotation = sent(request);
        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());
        ServiceContract first = contractRepository.findByServiceRequestId(request.getId()).orElseThrow();

        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());

        assertThat(contractRepository.count()).isEqualTo(1);
        ServiceContract second = contractRepository.findByServiceRequestId(request.getId()).orElseThrow();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getContractedAt()).isEqualTo(first.getContractedAt());
    }

    @Test
    void unaCotizacionAceptadaConGc358QueQuedoSinContratarSeCompletaAlRepetirLaAceptacion() throws Exception {
        ServiceRequest request = selectedRequest();
        Quotation quotation = sent(request);
        quotation.accept(Instant.now());
        quotationRepository.saveAndFlush(quotation);   // dato anterior a GC-359: aceptada sin contratar
        assertThat(contractRepository.count()).isZero();

        accept(CLIENT, request.getId(), quotation.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        assertThat(serviceRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.CONTRACTED);
        assertThat(contractRepository.findByServiceRequestId(request.getId())).isPresent();
    }

    @Test
    void yaContratadaElPrestadorNoEnviaOtraCotizacion() throws Exception {
        ServiceRequest request = selectedRequest();
        Quotation quotation = sent(request);
        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());

        send(PROVIDER, request.getId()).andExpect(status().isConflict());

        assertThat(contractRepository.count()).isEqualTo(1);
        assertThat(quotationRepository.count()).isEqualTo(1);
    }

    // ---------- Consultar la contratación ----------

    @Test
    void elClienteConsultaLaContratacionDeSuSolicitud() throws Exception {
        ServiceRequest request = selectedRequest();
        Quotation quotation = sent(request);
        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());
        ServiceContract contract = contractRepository.findByServiceRequestId(request.getId()).orElseThrow();

        clientContract(CLIENT, request.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(contract.getId().toString()))
                .andExpect(jsonPath("$.serviceRequestId").value(request.getId().toString()))
                .andExpect(jsonPath("$.quotationId").value(quotation.getId().toString()))
                .andExpect(jsonPath("$.clientId").value(CLIENT.toString()))
                .andExpect(jsonPath("$.providerId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.amount").value(150000.50))
                .andExpect(jsonPath("$.currency").value("COP"))
                .andExpect(jsonPath("$.scope").value("Cambio de tubería del lavamanos"))
                .andExpect(jsonPath("$.conditions").value("Incluye materiales; pago al terminar"))
                .andExpect(jsonPath("$.agreedAt").isNotEmpty())
                .andExpect(jsonPath("$.contractedAt").isNotEmpty())
                .andExpect(jsonPath("$.serviceRequest.status").value("CONTRACTED"))
                .andExpect(jsonPath("$.serviceRequest.exactLocation.address").value(ADDRESS));
    }

    @Test
    void elPrestadorContratadoRecibeLaDireccionExactaSoloConLaContratacion() throws Exception {
        ServiceRequest request = selectedRequest();
        Quotation quotation = sent(request);

        // Antes de aceptar: ni contratación ni dirección exacta para nadie.
        mockMvc.perform(get("/api/services/contracts").with(professional(PROVIDER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/services/requests/" + request.getId()).with(client(CLIENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exactLocation").doesNotExist());

        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());

        ServiceContract contract = contractRepository.findByServiceRequestId(request.getId()).orElseThrow();
        providerContract(PROVIDER, contract.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceRequest.exactLocation.address").value(ADDRESS))
                .andExpect(jsonPath("$.serviceRequest.exactLocation.latitude").value(4.65))
                .andExpect(jsonPath("$.serviceRequest.approximateLocation.city").value("Bogotá"));
        mockMvc.perform(get("/api/services/requests/" + request.getId()).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTRACTED"))
                .andExpect(jsonPath("$.exactLocation.address").value(ADDRESS));
    }

    @Test
    void siElClienteNoDioDireccionExactaLaContratacionNoLaInventa() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER, null);
        Quotation quotation = sent(request);
        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());

        clientContract(CLIENT, request.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceRequest.exactLocation").doesNotExist());
    }

    @Test
    void laRespuestaNoTraeDatosDeContactoNiDePagoSoloLoAcordado() throws Exception {
        ServiceRequest request = selectedRequest();
        Quotation quotation = sent(request);
        accept(CLIENT, request.getId(), quotation.getId()).andExpect(status().isOk());

        JsonNode body = json(clientContract(CLIENT, request.getId()));

        Set<String> keys = Set.copyOf(StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(body.fieldNames(), 0), false).toList());
        assertThat(keys).containsExactlyInAnyOrder("id", "serviceRequestId", "quotationId", "clientId", "providerId",
                "amount", "currency", "scope", "conditions", "agreedAt", "contractedAt", "serviceStatus", "timeline",
                "serviceRequest");
    }

    @Test
    void elPrestadorListaSoloSusContratacionesLaMasRecientePrimero() throws Exception {
        ServiceRequest first = selectedRequest();
        accept(CLIENT, first.getId(), sent(first).getId()).andExpect(status().isOk());
        Thread.sleep(5);
        ServiceRequest second = selectedRequest();
        accept(CLIENT, second.getId(), sent(second).getId()).andExpect(status().isOk());
        ServiceRequest ofOther = selectedRequest(CLIENT, OTHER_PROVIDER, null);
        Instant now = Instant.now();
        Quotation otherQuotation = quotationRepository.saveAndFlush(Quotation.send(ofOther, OTHER_PROVIDER,
                BigDecimal.TEN, CurrencyCode.COP, "x", "y", now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)),
                now));
        accept(CLIENT, ofOther.getId(), otherQuotation.getId()).andExpect(status().isOk());

        mockMvc.perform(get("/api/services/contracts").with(professional(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].serviceRequestId").value(second.getId().toString()))
                .andExpect(jsonPath("$[1].serviceRequestId").value(first.getId().toString()));
    }

    // ---------- Quién puede ver qué ----------

    @Test
    void sinContratacionTodavia404() throws Exception {
        ServiceRequest request = selectedRequest();
        sent(request);

        clientContract(CLIENT, request.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Contratación no encontrada"));
    }

    @Test
    void otroClienteRecibeLaMisma404QueSiLaSolicitudNoExistiera() throws Exception {
        ServiceRequest request = selectedRequest();
        accept(CLIENT, request.getId(), sent(request).getId()).andExpect(status().isOk());

        String foreign = clientContract(OTHER_CLIENT, request.getId())
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing = clientContract(OTHER_CLIENT, UUID.randomUUID())
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(foreign).get("detail")).isEqualTo(objectMapper.readTree(missing).get("detail"));
        mockMvc.perform(get("/api/services/requests/no-es-uuid/contract").with(client(CLIENT)))
                .andExpect(status().isNotFound());
    }

    @Test
    void otroPrestadorNoVeLaContratacionNiLaDireccionExacta() throws Exception {
        ServiceRequest request = selectedRequest();
        accept(CLIENT, request.getId(), sent(request).getId()).andExpect(status().isOk());
        ServiceContract contract = contractRepository.findByServiceRequestId(request.getId()).orElseThrow();

        providerContract(OTHER_PROVIDER, contract.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Contratación no encontrada"));
        providerContract(OTHER_PROVIDER, UUID.randomUUID()).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/services/contracts/no-es-uuid").with(professional(PROVIDER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Contratación no encontrada"));
        mockMvc.perform(get("/api/services/contracts").with(professional(OTHER_PROVIDER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void cadaRolSoloUsaSuRuta() throws Exception {
        ServiceRequest request = selectedRequest();
        accept(CLIENT, request.getId(), sent(request).getId()).andExpect(status().isOk());
        ServiceContract contract = contractRepository.findByServiceRequestId(request.getId()).orElseThrow();

        mockMvc.perform(get("/api/services/contracts/" + contract.getId()).with(client(CLIENT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/services/contracts").with(client(CLIENT))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/services/requests/" + request.getId() + "/contract").with(professional(PROVIDER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinTokenResponde401() throws Exception {
        ServiceRequest request = selectedRequest();

        mockMvc.perform(get("/api/services/requests/" + request.getId() + "/contract")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/services/contracts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/services/contracts/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void unSubjectConFormatoInvalidoResponde401() throws Exception {
        RequestPostProcessor badClient = jwt().jwt(token -> token.subject("cliente-sin-prefijo"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
        RequestPostProcessor badProvider = jwt().jwt(token -> token.subject("prestador-sin-prefijo"))
                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"));

        mockMvc.perform(get("/api/services/requests/" + UUID.randomUUID() + "/contract").with(badClient))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/services/contracts").with(badProvider)).andExpect(status().isUnauthorized());
    }

    // ---------- Concurrencia ----------

    @Test
    void variasAceptacionesSimultaneasDejanUnaSolaContratacion() throws Exception {
        ServiceRequest request = selectedRequest();
        Quotation quotation = sent(request);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> accept(CLIENT, request.getId(), quotation.getId()).andReturn().getResponse().getStatus());
        }

        assertThat(concurrently(tasks)).containsOnly(200);

        assertThat(contractRepository.count()).isEqualTo(1);
        assertThat(serviceRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.CONTRACTED);
    }

    @Test
    void aceptarYEnviarUnaNuevaNoDejanUnaContratadaJuntoAOtraEnviada() throws Exception {
        ServiceRequest request = selectedRequest();
        Quotation old = sent(request);
        List<Callable<Integer>> tasks = List.of(
                () -> accept(CLIENT, request.getId(), old.getId()).andReturn().getResponse().getStatus(),
                () -> send(PROVIDER, request.getId()).andReturn().getResponse().getStatus());

        List<Integer> statuses = concurrently(tasks);

        List<Quotation> all = quotationRepository.findByServiceRequestIdOrderByCreatedAtDesc(request.getId());
        long waiting = all.stream().filter(q -> q.getStatus() == QuotationStatus.SENT).count();
        if (contractRepository.count() == 1) {
            // Ganó la aceptación: contratada, sin ninguna cotización esperando respuesta y el envío rechazado.
            assertThat(statuses).containsExactly(200, 409);
            assertThat(waiting).isZero();
            assertThat(serviceRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                    .isEqualTo(ServiceRequestStatus.CONTRACTED);
        } else {
            // Ganó el envío: la vieja quedó reemplazada, la aceptación falló y no hay contratación.
            assertThat(statuses).containsExactly(409, 201);
            assertThat(quotationRepository.findById(old.getId()).orElseThrow().getStatus())
                    .isEqualTo(QuotationStatus.SUPERSEDED);
            assertThat(waiting).isEqualTo(1);
            assertThat(serviceRequestRepository.findById(request.getId()).orElseThrow().getStatus())
                    .isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
        }
    }
}
