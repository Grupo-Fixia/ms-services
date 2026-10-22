package com.fixia.services.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API de solicitudes de servicio. La autenticación se simula con el token ya validado ({@code jwt()}); la
 * validación real del token (firma, revocación) se prueba en {@code RevocationPipelineTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceRequestControllerTest {

    private static final String BASE = "/api/services/requests";
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRequestRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAllInBatch();
    }

    // ---------- Crear ----------

    @Test
    void crearUnaSolicitudDevuelve201ConLocationYEstadoPublicada() throws Exception {
        String body = mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(validBody())))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("http://localhost" + BASE + "/")))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.category").value("PLUMBING"))
                .andExpect(jsonPath("$.description").value("Fuga de agua en el baño"))
                .andExpect(jsonPath("$.approximateLocation.city").value("Bogotá"))
                .andExpect(jsonPath("$.approximateLocation.zone").value("Chapinero"))
                .andExpect(jsonPath("$.approximateLocation.latitude").value(4.65))
                .andExpect(jsonPath("$.approximateLocation.longitude").value(-74.08))
                .andExpect(jsonPath("$.photoKeys[0]").value("requests/a.jpg"))
                .andExpect(jsonPath("$.publishedAt").exists())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        ServiceRequest stored = repository.findById(id).orElseThrow();
        assertThat(stored.getClientId()).isEqualTo(CLIENT);
        assertThat(stored.getPhotoKeys()).containsExactly("requests/a.jpg", "requests/b.jpg");
    }

    @Test
    void laDireccionExactaSeGuardaPeroNoSeDevuelveHastaLaContratacion() throws Exception {
        String body = mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(validBody())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exactLocation").doesNotExist())
                .andExpect(jsonPath("$.selectedProviderId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        assertThat(jdbcTemplate.queryForObject("SELECT exact_address FROM service_requests WHERE id = ?",
                String.class, id)).isEqualTo("Calle 10 # 5-20, apto 301");
        mockMvc.perform(get(BASE + "/" + id).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exactLocation").doesNotExist());
        mockMvc.perform(get(BASE).with(client(CLIENT)))
                .andExpect(jsonPath("$[0].exactLocation").doesNotExist());
    }

    @Test
    void elClienteSalDelTokenAunqueElCuerpoTraigaOtro() throws Exception {
        Map<String, Object> body = validBody();
        body.put("clientId", OTHER_CLIENT.toString());
        body.put("status", "CONTRACTED");

        mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        assertThat(repository.findByClientIdOrderByPublishedAtDesc(CLIENT)).hasSize(1);
        assertThat(repository.findByClientIdOrderByPublishedAtDesc(OTHER_CLIENT)).isEmpty();
    }

    @Test
    void sinFotosNiDireccionExactaEsValido() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("exactLocation");
        body.remove("photoKeys");

        mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photoKeys", hasSize(0)));
    }

    @Test
    void conDatosInvalidosDevuelve400IndicandoQueCorregir() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "   ");
        body.put("approximateLocation", Map.of("city", " ", "zone", "Chapinero"));
        body.put("expectedAt", Instant.now().minus(Duration.ofDays(1)).toString());
        body.put("photoKeys", List.of("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg", "6.jpg"));

        mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errors[*].field", hasItems(
                        "category", "description", "approximateLocation.city", "expectedAt", "photoKeys")));

        assertThat(repository.count()).isZero();
    }

    @Test
    void lasCoordenadasIncompletasOFueraDeRangoSeRechazan() throws Exception {
        Map<String, Object> body = validBody();
        body.put("approximateLocation", Map.of("city", "Bogotá", "zone", "Chapinero", "latitude", 4.6));
        mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("approximateLocation.coordinatesComplete")));

        body.put("approximateLocation", Map.of("city", "Bogotá", "zone", "Chapinero",
                "latitude", 91.0, "longitude", 0.0));
        mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("approximateLocation.latitude")));

        body = validBody();
        body.put("exactLocation", Map.of("address", "Calle 1", "longitude", 10.0));
        mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("exactLocation.coordinatesComplete")));
    }

    @Test
    void unaClaveDeFotoConCaracteresNoPermitidosSeRechaza() throws Exception {
        for (String key : List.of("../secreto.jpg", "a/../b.jpg", " ", "foto con espacios.jpg")) {
            Map<String, Object> body = validBody();
            body.put("photoKeys", List.of(key));

            mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[*].field", hasItem("photoKeys[0]")));
        }
    }

    @Test
    void unCuerpoIlegibleOUnaCategoriaDesconocidaDevuelven400() throws Exception {
        mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"));

        Map<String, Object> body = validBody();
        body.put("category", "GARDENING");
        mockMvc.perform(post(BASE).with(client(CLIENT)).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Solicitud inválida"));
    }

    // ---------- Consultar ----------

    @Test
    void elListadoSoloTraeLasSolicitudesDelClienteLaMasRecientePrimero() throws Exception {
        Instant now = Instant.now();
        ServiceRequest older = save(CLIENT, "Primera", now.minusSeconds(120), List.of("requests/x.jpg"));
        ServiceRequest newer = save(CLIENT, "Segunda", now.minusSeconds(60), List.of());
        save(OTHER_CLIENT, "Ajena", now, List.of());

        mockMvc.perform(get(BASE).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(newer.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(older.getId().toString()))
                .andExpect(jsonPath("$[1].photoKeys[0]").value("requests/x.jpg"));
    }

    @Test
    void sinSolicitudesElListadoVieneVacio() throws Exception {
        mockMvc.perform(get(BASE).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void elClienteVeSuSolicitudPorId() throws Exception {
        ServiceRequest own = save(CLIENT, "Mía", Instant.now(), List.of("requests/x.jpg"));

        mockMvc.perform(get(BASE + "/" + own.getId()).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(own.getId().toString()))
                .andExpect(jsonPath("$.description").value("Mía"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void unaSolicitudAjenaUnaInexistenteOUnIdInvalidoRespondenIgual404() throws Exception {
        ServiceRequest foreign = save(OTHER_CLIENT, "Ajena", Instant.now(), List.of());

        for (String id : List.of(foreign.getId().toString(), UUID.randomUUID().toString(), "no-es-uuid")) {
            mockMvc.perform(get(BASE + "/" + id).with(client(CLIENT)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Solicitud no encontrada"))
                    .andExpect(jsonPath("$.detail").value("No existe la solicitud indicada"));
        }
    }

    @Test
    void laDireccionExactaSeEntregaAlClienteUnaVezContratada() throws Exception {
        ServiceRequest request = save(CLIENT, "Contratada", Instant.now(), List.of());
        request.selectProvider(PROVIDER, Instant.now());
        request.contract(Instant.now());
        repository.saveAndFlush(request);

        mockMvc.perform(get(BASE + "/" + request.getId()).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTRACTED"))
                .andExpect(jsonPath("$.selectedProviderId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.exactLocation.address").value("Calle 10 # 5-20, apto 301"));
    }

    // ---------- Autenticación y roles ----------

    @Test
    void sinTokenLaPeticionEsNoAutenticada() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.detail").value("Se requiere autenticación válida"));
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unTecnicoNoPuedeUsarLasRutasDelCliente() throws Exception {
        mockMvc.perform(post(BASE).with(role(CLIENT, "ROLE_PROFESSIONAL")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(validBody())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"));
        mockMvc.perform(get(BASE).with(role(CLIENT, "ROLE_PROFESSIONAL")))
                .andExpect(status().isForbidden());
        assertThat(repository.count()).isZero();
    }

    @Test
    void unSubjectConFormatoInesperadoSeTrataComoSesionInvalida() throws Exception {
        for (String subject : List.of("no-es-usr", "usr_no-es-uuid", "usr_")) {
            mockMvc.perform(get(BASE).with(jwt().jwt(token -> token.subject(subject))
                            .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        }
    }

    @Test
    void elHealthCheckEsPublico() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ---------- Utilidades ----------

    private ServiceRequest save(UUID clientId, String description, Instant publishedAt, List<String> photoKeys) {
        return repository.saveAndFlush(ServiceRequest.publish(clientId, ServiceCategory.PLUMBING, description,
                new ApproximateLocation("Bogotá", "Chapinero", 4.6533, -74.0836),
                new ExactLocation("Calle 10 # 5-20, apto 301", 4.7110, -74.0721),
                publishedAt.plus(Duration.ofDays(2)), photoKeys, publishedAt));
    }

    private static RequestPostProcessor client(UUID clientId) {
        return role(clientId, "ROLE_CLIENT");
    }

    private static RequestPostProcessor role(UUID userId, String role) {
        return jwt().jwt(token -> token.subject("usr_" + userId)).authorities(new SimpleGrantedAuthority(role));
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", "PLUMBING");
        body.put("description", "Fuga de agua en el baño");
        body.put("approximateLocation", Map.of("city", "Bogotá", "zone", "Chapinero",
                "latitude", 4.6533, "longitude", -74.0836));
        body.put("exactLocation", Map.of("address", "Calle 10 # 5-20, apto 301",
                "latitude", 4.7110, "longitude", -74.0721));
        body.put("expectedAt", Instant.now().plus(Duration.ofDays(2)).toString());
        body.put("photoKeys", List.of("requests/a.jpg", "requests/b.jpg"));
        return body;
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
