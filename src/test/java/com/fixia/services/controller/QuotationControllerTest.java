package com.fixia.services.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.dto.CreateQuotationRequest;
import com.fixia.services.repository.QuotationRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API de cotizaciones (GC-354): el prestador seleccionado crea y envía; él y el cliente dueño consultan. La
 * autenticación se simula con el token ya validado ({@code jwt()}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuotationControllerTest {

    private static final String BASE = "/api/services/quotations";
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER = UUID.randomUUID();

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

    @BeforeEach
    void cleanDatabase() {
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    private ServiceRequest published(UUID clientId) {
        Instant now = Instant.now();
        return ServiceRequest.publish(clientId, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero"), null, now.plus(Duration.ofDays(5)),
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), now);
    }

    /** Solicitud del cliente con el prestador ya seleccionado (lo que deja GC-348). */
    private ServiceRequest selectedRequest(UUID clientId, UUID providerId) {
        ServiceRequest request = published(clientId);
        request.selectProvider(providerId, Instant.now());
        return serviceRequestRepository.saveAndFlush(request);
    }

    private Quotation storedQuotation(ServiceRequest request, UUID providerId, Instant sentAt) {
        return quotationRepository.saveAndFlush(Quotation.send(request, providerId, new BigDecimal("90000"),
                CurrencyCode.COP, "Cambio de empaque", "Pago al terminar", sentAt.plus(Duration.ofHours(2)),
                sentAt.plus(Duration.ofHours(1)), sentAt));
    }

    private static RequestPostProcessor professional(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id))
                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"));
    }

    private static RequestPostProcessor client(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private static String body(UUID serviceRequestId, String amount, Instant agreedAt, Instant validUntil) {
        return """
                {"serviceRequestId":"%s","amount":%s,"currency":"COP","scope":"Cambio de tubería del lavamanos",
                 "conditions":"Incluye materiales; pago al terminar","agreedAt":"%s","validUntil":"%s"}
                """.formatted(serviceRequestId, amount, agreedAt, validUntil);
    }

    private static String validBody(UUID serviceRequestId) {
        Instant now = Instant.now();
        return body(serviceRequestId, "150000", now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)));
    }

    private ResultActions send(UUID providerId, String content) throws Exception {
        return mockMvc.perform(post(BASE).with(professional(providerId))
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .get("id").asText());
    }

    // ---------- Crear y enviar ----------

    @Test
    void elPrestadorSeleccionadoEnviaUnaCotizacion() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);

        UUID id = idOf(send(PROVIDER, validBody(request.getId()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString(BASE + "/")))
                .andExpect(jsonPath("$.serviceRequestId").value(request.getId().toString()))
                .andExpect(jsonPath("$.providerId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.amount").value(150000.0))
                .andExpect(jsonPath("$.currency").value("COP"))
                .andExpect(jsonPath("$.scope").value("Cambio de tubería del lavamanos"))
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.statusName").value("Enviada"))
                .andExpect(jsonPath("$.expired").value(false))
                .andExpect(jsonPath("$.respondedAt").doesNotExist()));

        Quotation stored = quotationRepository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(QuotationStatus.SENT);
        assertThat(stored.getAmount()).isEqualByComparingTo("150000.00");
    }

    @Test
    void laCabeceraLocationApuntaALaCotizacionCreada() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        ResultActions result = send(PROVIDER, validBody(request.getId())).andExpect(status().isCreated());

        assertThat(result.andReturn().getResponse().getHeader("Location")).endsWith(BASE + "/" + idOf(result));
    }

    @Test
    void unaNuevaCotizacionReemplazaALaEnviadaAnterior() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        UUID first = idOf(send(PROVIDER, validBody(request.getId())).andExpect(status().isCreated()));
        UUID second = idOf(send(PROVIDER, validBody(request.getId())).andExpect(status().isCreated()));

        assertThat(quotationRepository.findById(first).orElseThrow().getStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        assertThat(quotationRepository.findById(second).orElseThrow().getStatus()).isEqualTo(QuotationStatus.SENT);
        assertThat(quotationRepository.findByServiceRequestIdAndStatus(request.getId(), QuotationStatus.SENT)).hasSize(1);
    }

    @Test
    void laEnviadaAnteriorQueYaVencioQuedaVencidaYNoReemplazada() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation old = storedQuotation(request, PROVIDER, Instant.now().minus(Duration.ofDays(1)));

        send(PROVIDER, validBody(request.getId())).andExpect(status().isCreated());

        assertThat(quotationRepository.findById(old.getId()).orElseThrow().getStatus()).isEqualTo(QuotationStatus.EXPIRED);
    }

    @Test
    void variosEnviosSimultaneosDejanUnaSolaCotizacionEsperandoRespuesta() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> pending = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return send(PROVIDER, validBody(request.getId())).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : pending) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsOnly(201);
        assertThat(quotationRepository.findByServiceRequestIdAndStatus(request.getId(), QuotationStatus.SENT)).hasSize(1);
        assertThat(quotationRepository.findByServiceRequestIdAndStatus(request.getId(), QuotationStatus.SUPERSEDED))
                .hasSize(attempts - 1);
    }

    // ---------- Selección vigente ----------

    @Test
    void unPrestadorQueNoFueSeleccionadoNoPuedeCotizar() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);

        send(OTHER_PROVIDER, validBody(request.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Cotización no permitida"))
                .andExpect(jsonPath("$.detail").value("No tienes una selección vigente en esta solicitud"));
        assertThat(quotationRepository.count()).isZero();
    }

    @Test
    void unaSolicitudSinPrestadorSeleccionadoNoSeCotiza() throws Exception {
        ServiceRequest request = serviceRequestRepository.saveAndFlush(published(CLIENT));

        send(PROVIDER, validBody(request.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("No tienes una selección vigente en esta solicitud"));
    }

    @Test
    void unaSolicitudYaContratadaOCanceladaNoSeCotiza() throws Exception {
        ServiceRequest contracted = selectedRequest(CLIENT, PROVIDER);
        contracted.contract(Instant.now());
        serviceRequestRepository.saveAndFlush(contracted);
        ServiceRequest cancelled = selectedRequest(CLIENT, PROVIDER);
        cancelled.cancel(Instant.now());
        serviceRequestRepository.saveAndFlush(cancelled);

        send(PROVIDER, validBody(contracted.getId())).andExpect(status().isConflict());
        send(PROVIDER, validBody(cancelled.getId())).andExpect(status().isConflict());
    }

    @Test
    void siYaHayUnaCotizacionAceptadaNoSeEnviaOtra() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation accepted = Quotation.send(request, PROVIDER, new BigDecimal("90000"), CurrencyCode.COP, "Alcance",
                "Condiciones", Instant.now().plus(Duration.ofDays(2)), Instant.now().plus(Duration.ofDays(1)),
                Instant.now());
        accepted.accept(Instant.now());
        quotationRepository.saveAndFlush(accepted);

        send(PROVIDER, validBody(request.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La solicitud ya tiene una cotización aceptada"));
        assertThat(quotationRepository.count()).isEqualTo(1);
    }

    @Test
    void unaSolicitudInexistenteResponde404() throws Exception {
        send(PROVIDER, validBody(UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Solicitud no encontrada"));
    }

    // ---------- Validación ----------

    @Test
    void sinDatosObligatoriosResponde400ConCadaCampo() throws Exception {
        send(PROVIDER, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errors", hasSize(7)));
    }

    @Test
    void unValorNoPositivoOConMasDeDosDecimalesResponde400() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Instant now = Instant.now();

        send(PROVIDER, body(request.getId(), "0", now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
        send(PROVIDER, body(request.getId(), "100.123", now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    @Test
    void lasFechasLasValidaElDominio() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Instant now = Instant.now();

        send(PROVIDER, body(request.getId(), "150000", now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La vigencia no puede ser posterior a la fecha acordada"));
        send(PROVIDER, body(request.getId(), "150000", now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La fecha acordada debe ser posterior al envío"));
        assertThat(quotationRepository.count()).isZero();
    }

    @Test
    void unaMonedaNoAdmitidaResponde400() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);

        send(PROVIDER, validBody(request.getId()).replace("\"COP\"", "\"USD\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"));
    }

    // ---------- Seguridad ----------

    @Test
    void sinTokenResponde401() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(validBody(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unClienteNoPuedeCotizarNiVerCotizacionesDePrestador() throws Exception {
        mockMvc.perform(post(BASE).with(client(CLIENT))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE).with(client(CLIENT))).andExpect(status().isForbidden());
    }

    @Test
    void unSubjectConFormatoInvalidoResponde401() throws Exception {
        mockMvc.perform(get(BASE).with(jwt().jwt(token -> token.subject("otro"))
                        .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/services/requests/" + UUID.randomUUID() + "/quotations")
                        .with(jwt().jwt(token -> token.subject("usr_no-es-uuid"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/services/requests/" + UUID.randomUUID() + "/quotations")
                        .with(jwt().jwt(token -> token.subject("otro"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void elCuerpoNoExponeValorAlcanceNiCondicionesEnLogs() {
        CreateQuotationRequest request = new CreateQuotationRequest(UUID.randomUUID(), new BigDecimal("150000"),
                CurrencyCode.COP, "Alcance privado", "Condiciones privadas", Instant.now(), Instant.now());

        assertThat(request.toString()).doesNotContain("150000", "Alcance privado", "Condiciones privadas");
    }

    // ---------- Consultas del prestador ----------

    @Test
    void elPrestadorVeSoloSusCotizacionesYPuedeFiltrarPorSolicitud() throws Exception {
        ServiceRequest first = selectedRequest(CLIENT, PROVIDER);
        ServiceRequest second = selectedRequest(OTHER_CLIENT, PROVIDER);
        ServiceRequest foreign = selectedRequest(CLIENT, OTHER_PROVIDER);
        send(PROVIDER, validBody(first.getId())).andExpect(status().isCreated());
        UUID latest = idOf(send(PROVIDER, validBody(second.getId())).andExpect(status().isCreated()));
        send(OTHER_PROVIDER, validBody(foreign.getId())).andExpect(status().isCreated());

        mockMvc.perform(get(BASE).with(professional(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(latest.toString()));
        mockMvc.perform(get(BASE).param("serviceRequestId", first.getId().toString()).with(professional(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].serviceRequestId").value(first.getId().toString()));
    }

    @Test
    void elPrestadorConsultaUnaDeSusCotizacionesYNoLasAjenas() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        UUID id = idOf(send(PROVIDER, validBody(request.getId())).andExpect(status().isCreated()));

        mockMvc.perform(get(BASE + "/" + id).with(professional(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mockMvc.perform(get(BASE + "/" + id).with(professional(OTHER_PROVIDER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cotización no encontrada"));
        mockMvc.perform(get(BASE + "/no-es-uuid").with(professional(PROVIDER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cotización no encontrada"));
    }

    @Test
    void unaCotizacionEnviadaConLaVigenciaVencidaSeMarcaComoExpirada() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        Quotation old = storedQuotation(request, PROVIDER, Instant.now().minus(Duration.ofDays(1)));

        mockMvc.perform(get(BASE + "/" + old.getId()).with(professional(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.expired").value(true));
    }

    // ---------- Consultas del cliente ----------

    @Test
    void elClienteVeLasCotizacionesDeSuSolicitudLaMasRecientePrimero() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        send(PROVIDER, validBody(request.getId())).andExpect(status().isCreated());
        UUID latest = idOf(send(PROVIDER, validBody(request.getId())).andExpect(status().isCreated()));

        mockMvc.perform(get("/api/services/requests/" + request.getId() + "/quotations").with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(latest.toString()))
                .andExpect(jsonPath("$[0].status").value("SENT"))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].statusName").value("Reemplazada"));
    }

    @Test
    void otroClienteOUnPrestadorNoVenLasCotizacionesDeLaSolicitud() throws Exception {
        ServiceRequest request = selectedRequest(CLIENT, PROVIDER);
        send(PROVIDER, validBody(request.getId())).andExpect(status().isCreated());
        String path = "/api/services/requests/" + request.getId() + "/quotations";

        mockMvc.perform(get(path).with(client(OTHER_CLIENT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Solicitud no encontrada"));
        mockMvc.perform(get(path).with(professional(PROVIDER))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/services/requests/no-es-uuid/quotations").with(client(CLIENT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", containsString("solicitud")));
    }
}
