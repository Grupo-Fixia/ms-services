package com.fixia.services.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.client.EligibilityCheckException;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API de postulaciones (GC-342). La autenticación se simula con el token ya validado ({@code jwt()}) y la
 * elegibilidad que decide ms-users con un doble del cliente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationControllerTest {

    private static final String BASE = "/api/services/applications";
    private static final UUID TECHNICIAN = UUID.randomUUID();
    private static final UUID OTHER_TECHNICIAN = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TechnicianEligibilityClient eligibilityClient;

    @BeforeEach
    void cleanDatabase() {
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    private ServiceRequest publishedRequest() {
        Instant now = Instant.now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(UUID.randomUUID(),
                ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero", 4.6533, -74.0836),
                new ExactLocation("Calle 10 # 5-20, apto 301"), now.plus(Duration.ofDays(2)), List.of(), now));
    }

    private void eligible() {
        when(eligibilityClient.check(any(), any()))
                .thenReturn(new TechnicianEligibilityClient.Result(UUID.randomUUID(), true, List.of()));
    }

    private ResultActions apply(UUID technician, Object serviceRequestId) throws Exception {
        return apply(technician, serviceRequestId, 150000, "COP");
    }

    /** Postulación con el precio indicado; un valor {@code null} se omite del cuerpo. */
    private ResultActions apply(UUID technician, Object serviceRequestId, Object proposedAmount, Object currency)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceRequestId", serviceRequestId);
        if (proposedAmount != null) {
            body.put("proposedAmount", proposedAmount);
        }
        if (currency != null) {
            body.put("currency", currency);
        }
        return mockMvc.perform(post(BASE).with(technician(technician)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static RequestPostProcessor technician(UUID userId) {
        return role(userId, "ROLE_PROFESSIONAL");
    }

    private static RequestPostProcessor role(UUID userId, String role) {
        return jwt().jwt(token -> token.subject("usr_" + userId)).authorities(new SimpleGrantedAuthority(role));
    }

    // ---------- Postularse ----------

    @Test
    void unTecnicoElegibleSePostulaYRecibeLaConfirmacion() throws Exception {
        ServiceRequest request = publishedRequest();
        eligible();

        String body = apply(TECHNICIAN, request.getId())
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("http://localhost" + BASE + "/")))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.serviceRequest.id").value(request.getId().toString()))
                .andExpect(jsonPath("$.serviceRequest.category").value("PLUMBING"))
                .andExpect(jsonPath("$.serviceRequest.city").value("Bogotá"))
                .andExpect(jsonPath("$.serviceRequest.latitude").value(4.65))
                .andExpect(jsonPath("$.serviceRequest.exactLocation").doesNotExist())
                .andExpect(jsonPath("$.serviceRequest.description").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        assertThat(applicationRepository.findById(id).orElseThrow().getProviderId()).isEqualTo(TECHNICIAN);
        verify(eligibilityClient).check(eq(TECHNICIAN), any(ServiceRequest.class));
    }

    @Test
    void elTecnicoSaleDelTokenAunqueElCuerpoTraigaOtro() throws Exception {
        ServiceRequest request = publishedRequest();
        eligible();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceRequestId", request.getId());
        body.put("proposedAmount", 150000);
        body.put("currency", "COP");
        body.put("providerId", OTHER_TECHNICIAN);
        body.put("status", "SELECTED");

        mockMvc.perform(post(BASE).with(technician(TECHNICIAN)).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        assertThat(applicationRepository.findByProviderIdOrderByCreatedAtDesc(TECHNICIAN)).hasSize(1);
        assertThat(applicationRepository.findByProviderIdOrderByCreatedAtDesc(OTHER_TECHNICIAN)).isEmpty();
    }

    @Test
    void unTecnicoNoElegibleRecibe422ConLosMotivos() throws Exception {
        ServiceRequest request = publishedRequest();
        when(eligibilityClient.check(any(), any())).thenReturn(
                new TechnicianEligibilityClient.Result(request.getId(), false, List.of("ZONE", "AVAILABILITY")));

        apply(TECHNICIAN, request.getId())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("No elegible"))
                .andExpect(jsonPath("$.reasons", contains("ZONE", "AVAILABILITY")));

        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void siNoSePuedeVerificarLaElegibilidadNoSePostula() throws Exception {
        ServiceRequest request = publishedRequest();
        when(eligibilityClient.check(any(), any())).thenThrow(new EligibilityCheckException("ms-users caído"));

        apply(TECHNICIAN, request.getId())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Elegibilidad no verificable"))
                .andExpect(jsonPath("$.detail").value(startsWith("No fue posible verificar")));

        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void unaSolicitudInexistenteResponde404SinConsultarAMsUsers() throws Exception {
        apply(TECHNICIAN, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No existe la solicitud indicada"));

        verifyNoInteractions(eligibilityClient);
    }

    @Test
    void unaSolicitudQueYaTienePrestadorOCuyaFechaPasoResponde409() throws Exception {
        ServiceRequest selected = publishedRequest();
        transactionTemplate.executeWithoutResult(status -> serviceRequestRepository.findById(selected.getId())
                .orElseThrow().selectProvider(OTHER_TECHNICIAN, Instant.now()));
        ServiceRequest past = publishedRequest();
        jdbcTemplate.update("UPDATE service_requests SET expected_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))), past.getId());

        apply(TECHNICIAN, selected.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Solicitud no disponible"))
                .andExpect(jsonPath("$.detail").value("La solicitud ya no recibe postulaciones"));
        apply(TECHNICIAN, past.getId()).andExpect(status().isConflict());

        verifyNoInteractions(eligibilityClient);
        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void sinSolicitudOConUnIdInvalidoResponde400() throws Exception {
        apply(TECHNICIAN, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("serviceRequestId")));
        apply(TECHNICIAN, "no-es-uuid").andExpect(status().isBadRequest());
    }

    // ---------- Precio propuesto (GC-423) ----------

    @Test
    void elPrecioPropuestoSeGuardaYSeDevuelveAlConsultar() throws Exception {
        ServiceRequest request = publishedRequest();
        eligible();

        String body = apply(TECHNICIAN, request.getId(), new BigDecimal("185000.50"), "COP")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposedAmount").value(185000.50))
                .andExpect(jsonPath("$.currency").value("COP"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        assertThat(applicationRepository.findById(UUID.fromString(id)).orElseThrow().getProposedAmount())
                .isEqualByComparingTo("185000.50");
        mockMvc.perform(get(BASE + "/" + id).with(technician(TECHNICIAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedAmount").value(185000.50))
                .andExpect(jsonPath("$.currency").value("COP"));
        mockMvc.perform(get(BASE).with(technician(TECHNICIAN)))
                .andExpect(jsonPath("$[0].proposedAmount").value(185000.50));
    }

    @Test
    void sinPrecioOSinMonedaResponde400SinConsultarAMsUsers() throws Exception {
        ServiceRequest request = publishedRequest();

        apply(TECHNICIAN, request.getId(), null, "COP")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("proposedAmount")));
        apply(TECHNICIAN, request.getId(), 150000, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("currency")));

        verifyNoInteractions(eligibilityClient);
        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void unPrecioCeroNegativoConTresDecimalesODemasiadoGrandeResponde400() throws Exception {
        ServiceRequest request = publishedRequest();

        for (Object invalid : List.of(0, -1000, new BigDecimal("100.123"), new BigDecimal("99999999999"))) {
            apply(TECHNICIAN, request.getId(), invalid, "COP")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field").value(hasItem("proposedAmount")));
        }

        verifyNoInteractions(eligibilityClient);
        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void unaMonedaDistintaDeCopResponde400() throws Exception {
        apply(TECHNICIAN, publishedRequest().getId(), 150000, "USD")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"));

        verifyNoInteractions(eligibilityClient);
    }

    // ---------- Consultar ----------

    @Test
    void elTecnicoConsultaSoloSusPostulacionesLaMasRecientePrimero() throws Exception {
        ServiceRequest first = publishedRequest();
        ServiceRequest second = publishedRequest();
        eligible();
        apply(TECHNICIAN, first.getId()).andExpect(status().isCreated());
        apply(OTHER_TECHNICIAN, first.getId()).andExpect(status().isCreated());
        apply(TECHNICIAN, second.getId()).andExpect(status().isCreated());

        mockMvc.perform(get(BASE).with(technician(TECHNICIAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].serviceRequest.id").value(second.getId().toString()))
                .andExpect(jsonPath("$[1].serviceRequest.id").value(first.getId().toString()))
                .andExpect(jsonPath("$[0].status").value(ApplicationStatus.APPLIED.name()));
    }

    @Test
    void unaPostulacionAjenaOInexistenteResponde404() throws Exception {
        ServiceRequest request = publishedRequest();
        eligible();
        String body = apply(OTHER_TECHNICIAN, request.getId()).andReturn().getResponse().getContentAsString();
        String othersId = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(get(BASE + "/" + othersId).with(technician(TECHNICIAN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Postulación no encontrada"));
        mockMvc.perform(get(BASE + "/" + UUID.randomUUID()).with(technician(TECHNICIAN)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/" + othersId).with(technician(OTHER_TECHNICIAN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(othersId));
    }

    // ---------- Autorización ----------

    @Test
    void sinTokenResponde401YUnClienteResponde403() throws Exception {
        ServiceRequest request = publishedRequest();

        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceRequestId\":\"" + request.getId() + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE).with(role(TECHNICIAN, "ROLE_CLIENT")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceRequestId\":\"" + request.getId() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"));

        verifyNoInteractions(eligibilityClient);
        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void unTecnicoNoPuedeUsarLasRutasDeSolicitudesDelCliente() throws Exception {
        mockMvc.perform(get("/api/services/requests").with(technician(TECHNICIAN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unSubjectConFormatoInesperadoSeTrataComoSesionInvalida() throws Exception {
        mockMvc.perform(get(BASE).with(jwt().jwt(token -> token.subject("no-es-usr"))
                        .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"))))
                .andExpect(status().isUnauthorized());
    }
}
