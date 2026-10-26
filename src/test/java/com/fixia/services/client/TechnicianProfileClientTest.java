package com.fixia.services.client;

import com.fixia.services.config.UsersClientProperties;
import com.fixia.services.domain.ServiceCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

class TechnicianProfileClientTest {

    private static final String BASE_URL = "http://ms-users:8080";
    private static final String URL = BASE_URL + "/internal/technicians/public-profiles";

    private MockRestServiceServer server;
    private TechnicianProfileClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TechnicianProfileClient(builder.build(),
                new UsersClientProperties(BASE_URL, "secreto-interno", Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    private static String profile(UUID userId) {
        return "{\"userId\":\"" + userId + "\",\"displayName\":\"Carlos G.\",\"verificationStatus\":\"PENDING\","
                + "\"categories\":[\"PLUMBING\"],\"professionalDescription\":\"Plomero\",\"yearsOfExperience\":8,"
                + "\"city\":\"Bogotá\"}";
    }

    @Test
    void enviaLosIdsConLaClaveCompartidaYLeeLosPerfiles() {
        UUID technician = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "secreto-interno"))
                .andExpect(jsonPath("$.userIds.length()").value(2))
                .andExpect(jsonPath("$.userIds[0]").value(technician.toString()))
                .andRespond(withSuccess("{\"profiles\":[" + profile(technician) + "],\"otro\":\"dato\"}",
                        MediaType.APPLICATION_JSON));

        Map<UUID, TechnicianProfileClient.PublicProfile> profiles =
                client.findPublicProfiles(List.of(technician, unknown, technician));

        assertThat(profiles).containsOnlyKeys(technician);
        TechnicianProfileClient.PublicProfile found = profiles.get(technician);
        assertThat(found.displayName()).isEqualTo("Carlos G.");
        assertThat(found.categories()).containsExactly(ServiceCategory.PLUMBING);
        assertThat(found.yearsOfExperience()).isEqualTo(8);
        assertThat(found.city()).isEqualTo("Bogotá");
        server.verify();
    }

    @Test
    void consultaPorLotesDeCienTecnicos() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            ids.add(UUID.randomUUID());
        }
        server.expect(requestTo(URL)).andExpect(jsonPath("$.userIds.length()").value(100))
                .andRespond(withSuccess("{\"profiles\":[" + profile(ids.get(0)) + "]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andExpect(jsonPath("$.userIds.length()").value(50))
                .andRespond(withSuccess("{\"profiles\":[" + profile(ids.get(149)) + "]}", MediaType.APPLICATION_JSON));

        assertThat(client.findPublicProfiles(ids)).containsOnlyKeys(ids.get(0), ids.get(149));
        server.verify();
    }

    @Test
    void sinTecnicosNoLlamaAMsUsers() {
        server.expect(ExpectedCount.never(), requestTo(URL));

        assertThat(client.findPublicProfiles(List.of())).isEmpty();
        server.verify();
    }

    @Test
    void unErrorOUnaCaidaDeMsUsersSeTraduceEnPerfilesNoDisponibles() {
        for (HttpStatus failure : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.INTERNAL_SERVER_ERROR,
                HttpStatus.SERVICE_UNAVAILABLE)) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withStatus(failure));
            assertThatThrownBy(() -> client.findPublicProfiles(List.of(UUID.randomUUID())))
                    .as(failure.toString()).isInstanceOf(ProfileLookupException.class);
        }
        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("timeout")));
        assertThatThrownBy(() -> client.findPublicProfiles(List.of(UUID.randomUUID())))
                .isInstanceOf(ProfileLookupException.class);
    }

    @Test
    void unaRespuestaIncompletaSeTrataComoNoDisponible() {
        for (String body : List.of("{}", "{\"profiles\":null}", "{\"profiles\":[null]}",
                "{\"profiles\":[{\"displayName\":\"Sin id\"}]}")) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.findPublicProfiles(List.of(UUID.randomUUID())))
                    .as(body).isInstanceOf(ProfileLookupException.class);
        }
    }
}
