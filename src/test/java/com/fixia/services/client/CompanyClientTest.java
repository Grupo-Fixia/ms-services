package com.fixia.services.client;

import com.fixia.services.config.UsersClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CompanyClientTest {

    private static final String BASE_URL = "http://ms-users:8080";
    private static final String ASSOCIATION = BASE_URL + "/internal/companies/technician-association";
    private static final String PROFILES = BASE_URL + "/internal/companies/public-profiles";
    private static final String TECHNICIANS = BASE_URL + "/internal/companies/technicians";
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TECHNICIAN = UUID.randomUUID();

    private MockRestServiceServer server;
    private CompanyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CompanyClient(builder.build(),
                new UsersClientProperties(BASE_URL, "secreto-interno", Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    // ---------- Asociación ----------

    @Test
    void preguntaPorLaAsociacionConLaClaveCompartida() {
        server.expect(requestTo(ASSOCIATION))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "secreto-interno"))
                .andExpect(jsonPath("$.companyAdminUserId").value(COMPANY.toString()))
                .andExpect(jsonPath("$.technicianUserId").value(TECHNICIAN.toString()))
                .andRespond(withSuccess("{\"associated\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ASSOCIATION))
                .andRespond(withSuccess("{\"associated\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.isTechnicianAssociated(COMPANY, TECHNICIAN)).isTrue();
        assertThat(client.isTechnicianAssociated(COMPANY, TECHNICIAN)).isFalse();
        server.verify();
    }

    @Test
    void sinRespuestaValidaLaAsociacionNoSeDaPorBuena() {
        for (HttpStatus failure : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.INTERNAL_SERVER_ERROR)) {
            server.reset();
            server.expect(requestTo(ASSOCIATION)).andRespond(withStatus(failure));
            assertThatThrownBy(() -> client.isTechnicianAssociated(COMPANY, TECHNICIAN))
                    .isInstanceOf(EligibilityCheckException.class);
        }
        for (String body : List.of("{}", "{\"associated\":null}")) {
            server.reset();
            server.expect(requestTo(ASSOCIATION)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.isTechnicianAssociated(COMPANY, TECHNICIAN))
                    .as(body).isInstanceOf(EligibilityCheckException.class);
        }
        server.reset();
        server.expect(requestTo(ASSOCIATION)).andRespond(withException(new SocketTimeoutException("timeout")));
        assertThatThrownBy(() -> client.isTechnicianAssociated(COMPANY, TECHNICIAN))
                .isInstanceOf(EligibilityCheckException.class);
    }

    // ---------- Técnicos de la empresa ----------

    @Test
    void consultaLosTecnicosDeLaEmpresaConLaClaveCompartidaSinRepetidos() {
        UUID other = UUID.randomUUID();
        server.expect(requestTo(TECHNICIANS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "secreto-interno"))
                .andExpect(jsonPath("$.companyAdminUserId").value(COMPANY.toString()))
                .andRespond(withSuccess("{\"technicianUserIds\":[\"" + TECHNICIAN + "\",\"" + other + "\",\""
                        + TECHNICIAN + "\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(TECHNICIANS))
                .andRespond(withSuccess("{\"technicianUserIds\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.findTechnicians(COMPANY)).containsExactly(TECHNICIAN, other);
        assertThat(client.findTechnicians(COMPANY)).isEmpty();
        server.verify();
    }

    @Test
    void sinRespuestaValidaNoHayTecnicosDeLaEmpresa() {
        server.expect(requestTo(TECHNICIANS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.findTechnicians(COMPANY)).isInstanceOf(EligibilityCheckException.class);
        for (String body : List.of("{}", "{\"technicianUserIds\":null}", "{\"technicianUserIds\":[null]}")) {
            server.reset();
            server.expect(requestTo(TECHNICIANS)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.findTechnicians(COMPANY))
                    .as(body).isInstanceOf(EligibilityCheckException.class);
        }
    }

    // ---------- Perfiles ----------

    @Test
    void consultaLosPerfilesPorLotesYOmiteLosQueNoVuelven() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            ids.add(UUID.randomUUID());
        }
        server.expect(requestTo(PROFILES)).andExpect(jsonPath("$.companyAdminUserIds.length()").value(100))
                .andExpect(header("X-Internal-Api-Key", "secreto-interno"))
                .andRespond(withSuccess("{\"profiles\":[{\"companyAdminUserId\":\"" + ids.get(0)
                        + "\",\"name\":\"Servicios Andina\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(PROFILES)).andExpect(jsonPath("$.companyAdminUserIds.length()").value(20))
                .andRespond(withSuccess("{\"profiles\":[]}", MediaType.APPLICATION_JSON));

        var profiles = client.findPublicProfiles(ids);

        assertThat(profiles).containsOnlyKeys(ids.get(0));
        assertThat(profiles.get(ids.get(0)).name()).isEqualTo("Servicios Andina");
        server.verify();
    }

    @Test
    void sinRespuestaValidaLosPerfilesDeEmpresaNoEstanDisponibles() {
        server.expect(requestTo(PROFILES)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.findPublicProfiles(List.of(COMPANY)))
                .isInstanceOf(ProfileLookupException.class);
        for (String body : List.of("{}", "{\"profiles\":[null]}", "{\"profiles\":[{\"name\":\"Sin id\"}]}")) {
            server.reset();
            server.expect(requestTo(PROFILES)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.findPublicProfiles(List.of(COMPANY)))
                    .as(body).isInstanceOf(ProfileLookupException.class);
        }
    }
}
