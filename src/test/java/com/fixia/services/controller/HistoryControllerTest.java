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
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Historial propio de servicios (GC-448): el cliente y el prestador listan solo los servicios contratados en que
 * participaron, del más reciente al más antiguo, con paginación por cursor. Los servicios se siembran directamente en
 * el repositorio para controlar la fecha de contratación; el recorrido real por la API está en
 * {@link HistoryFlowTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoryControllerTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER = UUID.randomUUID();
    private static final String ADDRESS = "Calle 10 # 5-20, apto 301";
    private static final String PATH = "/api/services/history";

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
        transitionRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    /** Contratación sembrada con la fecha de contratación indicada y sin historial de transiciones (servicio antiguo). */
    private ServiceContract contracted(UUID clientId, UUID providerId, Instant contractedAt) {
        // la base guarda microsegundos; Instant.now() en Linux trae nanosegundos y lo leído no coincidiría con lo guardado
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ServiceRequest request = ServiceRequest.publish(clientId, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero", 4.6512, -74.0583), new ExactLocation(ADDRESS, 4.65, -74.08),
                now.plus(Duration.ofDays(5)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now);
        request.selectProvider(providerId, now);
        ServiceRequest saved = serviceRequestRepository.saveAndFlush(request);
        Quotation quotation = Quotation.send(saved, providerId, new BigDecimal("150000.50"), CurrencyCode.COP,
                "Cambio de tubería del lavamanos", "Incluye materiales", now.plus(Duration.ofDays(3)),
                now.plus(Duration.ofDays(1)), 1, now);
        quotation.accept(now);
        quotationRepository.saveAndFlush(quotation);
        saved.contract(now);
        serviceRequestRepository.saveAndFlush(saved);
        return contractRepository.saveAndFlush(ServiceContract.formalize(saved, quotation, contractedAt));
    }

    private ServiceContract contracted(UUID clientId, UUID providerId) {
        return contracted(clientId, providerId, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private static RequestPostProcessor as(UUID id, String role) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority(role));
    }

    private static RequestPostProcessor client(UUID id) {
        return as(id, "ROLE_CLIENT");
    }

    private static RequestPostProcessor professional(UUID id) {
        return as(id, "ROLE_PROFESSIONAL");
    }

    private ResultActions history(RequestPostProcessor user, String query) throws Exception {
        return mockMvc.perform(get(PATH + query).with(user));
    }

    private JsonNode page(RequestPostProcessor user, String query) throws Exception {
        return objectMapper.readTree(history(user, query).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> ids(JsonNode page, String field) {
        return page.get("items").findValuesAsText(field);
    }

    // ---------- Quién ve qué ----------

    @Test
    void elClienteVeSoloSusServiciosContratados() throws Exception {
        ServiceContract first = contracted(CLIENT, PROVIDER);
        ServiceContract second = contracted(CLIENT, OTHER_PROVIDER);
        contracted(OTHER_CLIENT, PROVIDER);

        JsonNode result = page(client(CLIENT), "");

        assertThat(ids(result, "contractId")).containsExactlyInAnyOrder(first.getId().toString(),
                second.getId().toString());
        assertThat(ids(result, "role")).containsOnly("CLIENT");
        // la contraparte del cliente es el prestador contratado
        assertThat(ids(result, "counterpartId")).containsExactlyInAnyOrder(PROVIDER.toString(),
                OTHER_PROVIDER.toString());
        assertThat(result.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void elPrestadorVeSoloLosServiciosEnQueFueContratado() throws Exception {
        ServiceContract first = contracted(CLIENT, PROVIDER);
        ServiceContract second = contracted(OTHER_CLIENT, PROVIDER);
        contracted(CLIENT, OTHER_PROVIDER);

        JsonNode result = page(professional(PROVIDER), "");

        assertThat(ids(result, "contractId")).containsExactlyInAnyOrder(first.getId().toString(),
                second.getId().toString());
        assertThat(ids(result, "role")).containsOnly("PROFESSIONAL");
        // la contraparte del prestador es el cliente de cada servicio
        assertThat(ids(result, "counterpartId")).containsExactlyInAnyOrder(CLIENT.toString(),
                OTHER_CLIENT.toString());
    }

    @Test
    void unTerceroNoVeNingunServicioAjeno() throws Exception {
        contracted(CLIENT, PROVIDER);

        history(client(OTHER_CLIENT), "").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        history(professional(OTHER_PROVIDER), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void elUsuarioSalePorSiempreDelTokenNuncaDeLosParametros() throws Exception {
        ServiceContract mine = contracted(CLIENT, PROVIDER);
        contracted(OTHER_CLIENT, OTHER_PROVIDER);

        JsonNode result = page(client(CLIENT), "?clientId=" + OTHER_CLIENT + "&userId=" + OTHER_CLIENT
                + "&providerId=" + OTHER_PROVIDER);

        assertThat(ids(result, "contractId")).containsExactly(mine.getId().toString());
    }

    @Test
    void unaSolicitudSinContratarNoApareceEnElHistorial() throws Exception {
        Instant now = Instant.now();
        serviceRequestRepository.saveAndFlush(ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING,
                "Aún sin prestador", new ApproximateLocation("Bogotá", "Chapinero", 4.65, -74.05), null,
                now.plus(Duration.ofDays(5)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now));

        history(client(CLIENT), "").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
    }

    // ---------- Contenido ----------

    @Test
    void cadaServicioTraeLoAcordadoYElPresupuestoPorSeparado() throws Exception {
        ServiceContract contract = contracted(CLIENT, PROVIDER);

        JsonNode item = page(client(CLIENT), "").get("items").get(0);

        assertThat(item.get("serviceRequestId").asText()).isEqualTo(contract.getServiceRequestId().toString());
        assertThat(item.get("contractId").asText()).isEqualTo(contract.getId().toString());
        assertThat(item.get("category").asText()).isEqualTo("PLUMBING");
        assertThat(item.get("status").asText()).isEqualTo("CONTRACTED");
        assertThat(item.get("budgetAmount").decimalValue()).isEqualByComparingTo("200000");
        assertThat(item.get("contractedAmount").decimalValue()).isEqualByComparingTo("150000.50");
        assertThat(item.get("currency").asText()).isEqualTo("COP");
        assertThat(Instant.parse(item.get("contractedAt").asText())).isEqualTo(contract.getContractedAt());
        assertThat(Instant.parse(item.get("agreedAt").asText())).isEqualTo(contract.getAgreedAt());
    }

    @Test
    void unServicioSinHistorialNoInventaFechasDeEjecucion() throws Exception {
        contracted(CLIENT, PROVIDER);

        history(client(CLIENT), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].startedAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].closedAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].lastTransitionAt").value(nullValue()));
    }

    @Test
    void laRespuestaNoExponeDireccionExactaNiMotivosNiHistorialNiEvaluaciones() throws Exception {
        contracted(CLIENT, PROVIDER);

        for (RequestPostProcessor user : List.of(client(CLIENT), professional(PROVIDER))) {
            String body = history(user, "").andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).doesNotContain(ADDRESS, "exactLocation", "address", "reason", "timeline", "rating",
                    "evaluation");
        }
    }

    // ---------- Orden y paginación ----------

    @Test
    void ordenaDelMasRecienteAlMasAntiguo() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ServiceContract oldest = contracted(CLIENT, PROVIDER, base.minus(Duration.ofDays(2)));
        ServiceContract newest = contracted(CLIENT, PROVIDER, base);
        ServiceContract middle = contracted(CLIENT, PROVIDER, base.minus(Duration.ofDays(1)));

        for (RequestPostProcessor user : List.of(client(CLIENT), professional(PROVIDER))) {
            assertThat(ids(page(user, ""), "contractId")).containsExactly(newest.getId().toString(),
                    middle.getId().toString(), oldest.getId().toString());
        }
    }

    @Test
    void recorreTodasLasPaginasSinRepetirNiSaltarEIncluyendoEmpates() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // tres servicios con la misma fecha de contratación: solo el id los desempata
        List<ServiceContract> contracts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            contracts.add(contracted(CLIENT, PROVIDER, base));
        }
        for (int i = 1; i <= 4; i++) {
            contracts.add(contracted(CLIENT, PROVIDER, base.minus(Duration.ofHours(i))));
        }

        for (RequestPostProcessor user : List.of(client(CLIENT), professional(PROVIDER))) {
            List<String> everything = ids(page(user, "?limit=50"), "contractId");
            assertThat(everything).hasSize(7).doesNotHaveDuplicates();

            List<String> walked = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                JsonNode current = page(user, "?limit=3" + (cursor == null ? "" : "&cursor=" + cursor));
                assertThat(current.get("items").size()).isLessThanOrEqualTo(3);
                walked.addAll(ids(current, "contractId"));
                cursor = current.get("nextCursor").isNull() ? null : current.get("nextCursor").asText();
                pages++;
            } while (cursor != null && pages < 10);

            assertThat(pages).isEqualTo(3);
            assertThat(walked).containsExactlyElementsOf(everything);
            assertThat(walked).containsExactlyInAnyOrderElementsOf(
                    contracts.stream().map(contract -> contract.getId().toString()).toList());
        }
    }

    @Test
    void unServicioNuevoEntrePaginasNoRepiteNiSaltaLosYaEntregados() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ServiceContract newest = contracted(CLIENT, PROVIDER, base.minus(Duration.ofHours(1)));
        ServiceContract middle = contracted(CLIENT, PROVIDER, base.minus(Duration.ofHours(2)));
        ServiceContract oldest = contracted(CLIENT, PROVIDER, base.minus(Duration.ofHours(3)));

        JsonNode first = page(client(CLIENT), "?limit=2");
        contracted(CLIENT, PROVIDER, base); // llega un servicio más reciente entre las dos consultas
        JsonNode second = page(client(CLIENT), "?limit=2&cursor=" + first.get("nextCursor").asText());

        assertThat(ids(first, "contractId")).containsExactly(newest.getId().toString(), middle.getId().toString());
        assertThat(ids(second, "contractId")).containsExactly(oldest.getId().toString());
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void elLimiteMasPequenoDaUnaPaginaConCursorYElMaximoSeAcepta() throws Exception {
        contracted(CLIENT, PROVIDER);
        contracted(CLIENT, PROVIDER);

        history(client(CLIENT), "?limit=1").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
        history(client(CLIENT), "?limit=50").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    // ---------- Parámetros inválidos ----------

    @Test
    void unLimiteInvalidoEsUn400() throws Exception {
        for (String limit : List.of("0", "51", "-1", "abc", "", "1.5", "999999999999")) {
            history(client(CLIENT), "?limit=" + limit).andExpect(status().isBadRequest());
        }
    }

    @Test
    void unCursorInvalidoEsUn400() throws Exception {
        String notUuid = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-10-30T10:00:00Z|no-es-un-uuid".getBytes(StandardCharsets.UTF_8));
        String outOfRange = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("1900-01-01T00:00:00Z|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("no-es-un-cursor", "%%%", notUuid, outOfRange)) {
            history(client(CLIENT), "?cursor=" + cursor).andExpect(status().isBadRequest());
        }
    }

    // ---------- Seguridad ----------

    @Test
    void sinSesionEsUn401() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void unTokenConSubInvalidoEsUn401() throws Exception {
        mockMvc.perform(get(PATH).with(jwt().jwt(token -> token.subject("no-es-usr"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lasEmpresasYOtrosRolesNoTienenHistorial() throws Exception {
        contracted(CLIENT, PROVIDER);

        history(as(UUID.randomUUID(), "ROLE_COMPANY"), "").andExpect(status().isForbidden());
        history(as(UUID.randomUUID(), "ROLE_ADMIN"), "").andExpect(status().isForbidden());
    }
}
