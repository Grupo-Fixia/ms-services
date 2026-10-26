package com.fixia.services.controller;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consulta de postulantes de una solicitud por su cliente (GC-347). La autenticación se simula con el token ya
 * validado ({@code jwt()}); la validación real del token se prueba en {@code RevocationPipelineTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicantsControllerTest {

    private static final BigDecimal PRICE = new BigDecimal("150000");

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final UUID THIRD = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    private ServiceRequest request(UUID clientId) {
        Instant now = Instant.now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(clientId, ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero", 4.6533, -74.0836),
                new ExactLocation("Calle 10 # 5-20, apto 301"), now.plus(Duration.ofDays(2)), List.of(), now));
    }

    /** Postulación del técnico, con un instante propio para fijar el orden de llegada. */
    private ServiceRequestApplication applied(ServiceRequest request, UUID provider, Instant at) {
        return applicationRepository.saveAndFlush(ServiceRequestApplication.submit(request, provider,
                PRICE, CurrencyCode.COP, at));
    }

    private static String applicants(ServiceRequest request) {
        return "/api/services/requests/" + request.getId() + "/applications";
    }

    private static RequestPostProcessor client(UUID userId) {
        return role(userId, "ROLE_CLIENT");
    }

    private static RequestPostProcessor role(UUID userId, String role) {
        return jwt().jwt(token -> token.subject("usr_" + userId)).authorities(new SimpleGrantedAuthority(role));
    }

    // ---------- Consultar ----------

    @Test
    void elClienteVeLosPostulantesDeSuSolicitudEnOrdenDeLlegada() throws Exception {
        ServiceRequest request = request(CLIENT);
        Instant base = Instant.now();
        applied(request, SECOND, base.plusSeconds(10));
        applied(request, FIRST, base);
        applied(request, THIRD, base.plusSeconds(20));

        mockMvc.perform(get(applicants(request)).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].providerId",
                        contains(FIRST.toString(), SECOND.toString(), THIRD.toString())))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].status").value("APPLIED"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].updatedAt").exists());
    }

    @Test
    void laRespuestaNoIncluyeDatosDeLaSolicitudNiUbicacion() throws Exception {
        ServiceRequest request = request(CLIENT);
        applied(request, FIRST, Instant.now());

        mockMvc.perform(get(applicants(request)).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceRequest").doesNotExist())
                .andExpect(jsonPath("$[0].exactLocation").doesNotExist())
                .andExpect(jsonPath("$[0].approximateLocation").doesNotExist())
                .andExpect(jsonPath("$[0].description").doesNotExist())
                .andExpect(jsonPath("$[0].version").doesNotExist());
    }

    @Test
    void elClienteVeElPrecioQuePropusoCadaPostulante() throws Exception {
        ServiceRequest request = request(CLIENT);
        Instant base = Instant.now();
        applicationRepository.saveAndFlush(ServiceRequestApplication.submit(request, FIRST,
                new BigDecimal("120000"), CurrencyCode.COP, base));
        applicationRepository.saveAndFlush(ServiceRequestApplication.submit(request, SECOND,
                new BigDecimal("98500.50"), CurrencyCode.COP, base.plusSeconds(10)));

        mockMvc.perform(get(applicants(request)).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].proposedAmount").value(120000.0))
                .andExpect(jsonPath("$[1].proposedAmount").value(98500.50))
                .andExpect(jsonPath("$[*].currency", contains("COP", "COP")));
    }

    @Test
    void unaPostulacionAnteriorAlPrecioSeMuestraSinPrecioYNoConUnoInventado() throws Exception {
        ServiceRequest request = request(CLIENT);
        ServiceRequestApplication old = applied(request, FIRST, Instant.now());
        jdbcTemplate.update("UPDATE applications SET proposed_amount = NULL, currency = NULL WHERE id = ?",
                old.getId());

        mockMvc.perform(get(applicants(request)).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].proposedAmount").value(nullValue()))
                .andExpect(jsonPath("$[0].currency").value(nullValue()));
    }

    @Test
    void unaSolicitudSinPostulantesDevuelveUnaListaVacia() throws Exception {
        mockMvc.perform(get(applicants(request(CLIENT))).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void elEstadoDeCadaPostulacionSeReflejaAunqueLaSolicitudYaTengaPrestador() throws Exception {
        ServiceRequest request = request(CLIENT);
        Instant base = Instant.now();
        applied(request, FIRST, base);
        applied(request, SECOND, base.plusSeconds(10));
        applied(request, THIRD, base.plusSeconds(20));
        transactionTemplate.executeWithoutResult(tx -> {
            applicationRepository.findByServiceRequestIdAndProviderId(request.getId(), FIRST).orElseThrow()
                    .select(Instant.now());
            applicationRepository.findByServiceRequestIdAndProviderId(request.getId(), SECOND).orElseThrow()
                    .reject(Instant.now());
            serviceRequestRepository.findById(request.getId()).orElseThrow().selectProvider(FIRST, Instant.now());
        });

        mockMvc.perform(get(applicants(request)).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status", contains("SELECTED", "REJECTED", "APPLIED")));
    }

    @Test
    void soloAparecenLasPostulacionesDeLaSolicitudConsultada() throws Exception {
        ServiceRequest request = request(CLIENT);
        ServiceRequest another = request(CLIENT);
        applied(request, FIRST, Instant.now());
        applied(another, SECOND, Instant.now());

        mockMvc.perform(get(applicants(request)).with(client(CLIENT)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].providerId").value(FIRST.toString()));
    }

    // ---------- Privacidad entre clientes ----------

    @Test
    void unaSolicitudAjenaInexistenteOConIdInvalidoResponde404Identico() throws Exception {
        ServiceRequest foreign = request(OTHER_CLIENT);
        applied(foreign, FIRST, Instant.now());

        String expected = "No existe la solicitud indicada";
        mockMvc.perform(get(applicants(foreign)).with(client(CLIENT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Solicitud no encontrada"))
                .andExpect(jsonPath("$.detail").value(expected));
        mockMvc.perform(get("/api/services/requests/" + UUID.randomUUID() + "/applications").with(client(CLIENT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(expected));
        mockMvc.perform(get("/api/services/requests/no-es-uuid/applications").with(client(CLIENT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(expected));
    }

    // ---------- Autorización ----------

    @Test
    void sinTokenResponde401() throws Exception {
        mockMvc.perform(get(applicants(request(CLIENT)))).andExpect(status().isUnauthorized());
    }

    @Test
    void unTecnicoNoPuedeConsultarLosPostulantes() throws Exception {
        ServiceRequest request = request(CLIENT);
        applied(request, FIRST, Instant.now());

        mockMvc.perform(get(applicants(request)).with(role(FIRST, "ROLE_PROFESSIONAL")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"));
    }

    @Test
    void unSubjectConFormatoInesperadoSeTrataComoSesionInvalida() throws Exception {
        mockMvc.perform(get(applicants(request(CLIENT))).with(jwt().jwt(token -> token.subject("no-es-usr"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"))))
                .andExpect(status().isUnauthorized());
    }
}
