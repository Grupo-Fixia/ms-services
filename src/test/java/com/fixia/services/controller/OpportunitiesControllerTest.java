package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.client.EligibilityCheckException;
import com.fixia.services.client.TechnicianEligibilityClient;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API de oportunidades compatibles de un técnico (GC-338). La autenticación se simula con el token ya validado
 * ({@code jwt()}) y la elegibilidad que decide ms-users con un doble del cliente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpportunitiesControllerTest {

    private static final BigDecimal PRICE = new BigDecimal("150000");

    private static final String URL = "/api/services/opportunities";
    private static final UUID TECHNICIAN = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TechnicianEligibilityClient eligibilityClient;

    @BeforeEach
    void cleanDatabase() {
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    private ServiceRequest published(ServiceCategory category, String city, Instant publishedAt) {
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(UUID.randomUUID(), category,
                "Fuga de agua en el baño", new ApproximateLocation(city, "Chapinero", 4.6533, -74.0836),
                new ExactLocation("Calle 10 # 5-20, apto 301"), publishedAt.plus(Duration.ofDays(2)),
                new BigDecimal("200000"), CurrencyCode.COP,
                List.of("requests/a.jpg"), publishedAt));
    }

    /** ms-users responde elegible solo para las solicitudes indicadas. */
    private void eligibleOnly(Set<UUID> eligibleIds) {
        when(eligibilityClient.checkAll(any(), any())).thenAnswer(call -> {
            List<ServiceRequest> asked = call.getArgument(1);
            Map<UUID, TechnicianEligibilityClient.Result> results = new HashMap<>();
            for (ServiceRequest candidate : asked) {
                boolean eligible = eligibleIds.contains(candidate.getId());
                results.put(candidate.getId(), new TechnicianEligibilityClient.Result(candidate.getId(), eligible,
                        eligible ? List.of() : List.of("ZONE")));
            }
            return results;
        });
    }

    private static Set<UUID> idsOf(List<ServiceRequest> requests) {
        return requests.stream().map(ServiceRequest::getId).collect(Collectors.toSet());
    }

    private ResultActions page(String query) throws Exception {
        return mockMvc.perform(get(URL + query).with(technician(TECHNICIAN)));
    }

    private static RequestPostProcessor technician(UUID userId) {
        return role(userId, "ROLE_PROFESSIONAL");
    }

    private static RequestPostProcessor role(UUID userId, String role) {
        return jwt().jwt(token -> token.subject("usr_" + userId)).authorities(new SimpleGrantedAuthority(role));
    }

    // ---------- Consultar ----------

    @Test
    void cadaOportunidadMuestraElPresupuestoOrientativoYLasAnterioresLoDejanNulo() throws Exception {
        Instant now = Instant.now();
        ServiceRequest withBudget = published(ServiceCategory.PLUMBING, "Bogotá", now.minusSeconds(60));
        ServiceRequest historic = published(ServiceCategory.PLUMBING, "Bogotá", now.minusSeconds(120));
        jdbcTemplate.update("UPDATE service_requests SET budget_amount = NULL, budget_currency = NULL WHERE id = ?",
                historic.getId());
        eligibleOnly(Set.of(withBudget.getId(), historic.getId()));

        page("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(withBudget.getId().toString()))
                .andExpect(jsonPath("$.items[0].budgetAmount").value(200000.0))
                .andExpect(jsonPath("$.items[0].budgetCurrency").value("COP"))
                .andExpect(jsonPath("$.items[1].id").value(historic.getId().toString()))
                .andExpect(jsonPath("$.items[1].budgetAmount").value(nullValue()))
                .andExpect(jsonPath("$.items[1].budgetCurrency").value(nullValue()))
                // El presupuesto no abre la dirección exacta ni el cliente: la privacidad no cambia.
                .andExpect(jsonPath("$.items[0].exactLocation").doesNotExist())
                .andExpect(jsonPath("$.items[0].clientId").doesNotExist());
    }

    @Test
    void devuelveSoloLasElegiblesLaMasRecientePrimeroEnUnObjetoConItemsYCursorNulo() throws Exception {
        Instant now = Instant.now();
        ServiceRequest older = published(ServiceCategory.PLUMBING, "Bogotá", now.minusSeconds(120));
        ServiceRequest newer = published(ServiceCategory.PLUMBING, "Bogotá", now.minusSeconds(60));
        ServiceRequest notEligible = published(ServiceCategory.ELECTRICAL, "Cali", now.minusSeconds(30));
        eligibleOnly(Set.of(older.getId(), newer.getId()));

        page("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].id", contains(newer.getId().toString(), older.getId().toString())))
                .andExpect(jsonPath("$.items[0].category").value("PLUMBING"))
                .andExpect(jsonPath("$.items[0].description").value("Fuga de agua en el baño"))
                .andExpect(jsonPath("$.items[0].city").value("Bogotá"))
                .andExpect(jsonPath("$.items[0].zone").value("Chapinero"))
                .andExpect(jsonPath("$.items[0].latitude").value(4.65))
                .andExpect(jsonPath("$.items[0].expectedAt").exists())
                .andExpect(jsonPath("$.items[0].publishedAt").exists())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ServiceRequest>> asked = ArgumentCaptor.forClass(List.class);
        verify(eligibilityClient).checkAll(eq(TECHNICIAN), asked.capture());
        assertThat(asked.getValue()).extracting(ServiceRequest::getId).contains(notEligible.getId());
    }

    @Test
    void laRespuestaNoIncluyeDatosPrivadosDelClienteNiLaDireccionExactaNiFotos() throws Exception {
        ServiceRequest request = published(ServiceCategory.PLUMBING, "Bogotá", Instant.now().minusSeconds(60));
        eligibleOnly(Set.of(request.getId()));

        String body = page("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].clientId").doesNotExist())
                .andExpect(jsonPath("$.items[0].exactLocation").doesNotExist())
                .andExpect(jsonPath("$.items[0].photoKeys").doesNotExist())
                .andExpect(jsonPath("$.items[0].status").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Calle 10").doesNotContain(request.getClientId().toString())
                .doesNotContain("requests/a.jpg");
    }

    @Test
    void noOfreceLasSolicitudesALasQueElTecnicoYaSePostuloPeroSiALosDemas() throws Exception {
        Instant now = Instant.now();
        ServiceRequest applied = published(ServiceCategory.PLUMBING, "Bogotá", now.minusSeconds(120));
        ServiceRequest free = published(ServiceCategory.PLUMBING, "Bogotá", now.minusSeconds(60));
        applicationRepository.saveAndFlush(ServiceRequestApplication.submit(applied, TECHNICIAN,
                PRICE, CurrencyCode.COP, now));
        eligibleOnly(Set.of(applied.getId(), free.getId()));

        page("").andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(free.getId().toString()));
        mockMvc.perform(get(URL).with(technician(UUID.randomUUID())))
                .andExpect(jsonPath("$.items", hasSize(2)));
    }

    @Test
    void sinSolicitudesAbiertasResponde200ConPaginaVaciaSinConsultarAMsUsers() throws Exception {
        page("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        verifyNoInteractions(eligibilityClient);
    }

    // ---------- Paginación ----------

    @Test
    void recorrerLasPaginasConElCursorEntregaTodasLasElegiblesSinRepetirNiSaltar() throws Exception {
        Instant now = Instant.now();
        List<ServiceRequest> all = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            all.add(published(ServiceCategory.PLUMBING, "Bogotá", now.minusSeconds(10 + i)));
        }
        eligibleOnly(idsOf(all));
        List<String> delivered = new ArrayList<>();

        String cursor = null;
        int pages = 0;
        do {
            String query = "?limit=3" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode body = objectMapper.readTree(page(query).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            body.get("items").forEach(item -> delivered.add(item.get("id").asText()));
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(pages).isEqualTo(3);
        assertThat(delivered).containsExactlyElementsOf(all.stream().map(r -> r.getId().toString()).toList());
    }

    @Test
    void elLimitePorDefectoEs20YElMaximoEs50() throws Exception {
        Instant now = Instant.now();
        List<ServiceRequest> all = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            all.add(published(ServiceCategory.PLUMBING, "Bogotá", now.minusSeconds(10 + i)));
        }
        eligibleOnly(idsOf(all));

        page("").andExpect(jsonPath("$.items", hasSize(20)));
        page("?limit=50").andExpect(jsonPath("$.items", hasSize(50)));
        page("?limit=1").andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void unLimiteOUnCursorInvalidosResponden400SinConsultarAMsUsers() throws Exception {
        for (String query : List.of("?limit=0", "?limit=51", "?limit=-1", "?limit=abc", "?limit=2.5",
                "?cursor=basura", "?cursor=MXx4")) {
            page(query)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Consulta inválida"));
        }

        verifyNoInteractions(eligibilityClient);
    }

    // ---------- Fallos y autorización ----------

    @Test
    void siNoSePuedeVerificarLaElegibilidadResponde503SinDevolverSolicitudes() throws Exception {
        published(ServiceCategory.PLUMBING, "Bogotá", Instant.now().minusSeconds(60));
        when(eligibilityClient.checkAll(any(), any())).thenThrow(new EligibilityCheckException("ms-users caído"));

        page("")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Elegibilidad no verificable"));
    }

    @Test
    void laRutaVieja_YaNoExiste() throws Exception {
        mockMvc.perform(get("/api/services/applications/opportunities").with(technician(TECHNICIAN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void sinTokenResponde401UnClienteResponde403YUnSubjectInvalidoResponde401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).with(role(TECHNICIAN, "ROLE_CLIENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"));
        mockMvc.perform(get(URL).with(jwt().jwt(token -> token.subject("no-es-usr"))
                        .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(eligibilityClient);
    }
}
