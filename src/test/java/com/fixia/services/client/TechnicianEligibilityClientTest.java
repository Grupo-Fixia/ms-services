package com.fixia.services.client;

import com.fixia.services.config.UsersClientProperties;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
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

class TechnicianEligibilityClientTest {

    private static final String BASE_URL = "http://ms-users:8080";
    private static final UUID TECHNICIAN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String URL = BASE_URL + "/internal/technicians/" + TECHNICIAN + "/eligibility";
    private static final Instant EXPECTED_AT = Instant.parse("2026-10-20T14:00:00Z");

    private MockRestServiceServer server;
    private TechnicianEligibilityClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TechnicianEligibilityClient(builder.build(),
                new UsersClientProperties(BASE_URL, "secreto-interno", Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    private static ServiceRequest request(UUID id) {
        ServiceRequest request = ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.ELECTRICAL, "Corto circuito",
                new ApproximateLocation("Bogotá", "Chapinero", 4.6533, -74.0836), null, EXPECTED_AT,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(),
                EXPECTED_AT.minus(Duration.ofDays(1)));
        ReflectionTestUtils.setField(request, "id", id);
        return request;
    }

    @Test
    void enviaLosDatosDeLaSolicitudConLaClaveCompartidaYLeeElResultado() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "secreto-interno"))
                .andExpect(jsonPath("$.requests[0].requestId").value(id.toString()))
                .andExpect(jsonPath("$.requests[0].category").value("ELECTRICAL"))
                .andExpect(jsonPath("$.requests[0].city").value("Bogotá"))
                .andExpect(jsonPath("$.requests[0].latitude").value(4.65))
                .andExpect(jsonPath("$.requests[0].longitude").value(-74.08))
                .andExpect(jsonPath("$.requests[0].expectedAt").value("2026-10-20T14:00:00Z"))
                .andRespond(withSuccess("{\"results\":[{\"requestId\":\"" + id + "\",\"eligible\":false,"
                        + "\"reasons\":[\"ZONE\",\"AVAILABILITY\"]}]}", MediaType.APPLICATION_JSON));

        TechnicianEligibilityClient.Result result = client.check(TECHNICIAN, request(id));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly("ZONE", "AVAILABILITY");
        server.verify();
    }

    @Test
    void evaluaVariasSolicitudesEnUnaSolaLlamada() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andRespond(withSuccess("{\"results\":[{\"requestId\":\"" + first + "\",\"eligible\":true,"
                        + "\"reasons\":[]},{\"requestId\":\"" + second + "\",\"eligible\":false,\"reasons\":"
                        + "[\"CATEGORY\"]}],\"otro\":\"dato\"}", MediaType.APPLICATION_JSON));

        Map<UUID, TechnicianEligibilityClient.Result> results =
                client.checkAll(TECHNICIAN, List.of(request(first), request(second)));

        assertThat(results.get(first).eligible()).isTrue();
        assertThat(results.get(second).reasons()).containsExactly("CATEGORY");
    }

    @Test
    void sinClaveConfiguradaEnviaLaCabeceraVacia() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer noKeyServer = MockRestServiceServer.bindTo(builder).build();
        TechnicianEligibilityClient noKey = new TechnicianEligibilityClient(builder.build(),
                new UsersClientProperties(BASE_URL, null, Duration.ofSeconds(1), Duration.ofSeconds(2)));
        UUID id = UUID.randomUUID();
        noKeyServer.expect(requestTo(URL)).andExpect(header("X-Internal-Api-Key", ""))
                .andRespond(withSuccess("{\"results\":[{\"requestId\":\"" + id + "\",\"eligible\":true}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(noKey.check(TECHNICIAN, request(id)).eligible()).isTrue();
    }

    @Test
    void siMsUsersFallaOTardaNoSeSabeLaElegibilidad() {
        for (HttpStatus failure : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND, HttpStatus.INTERNAL_SERVER_ERROR)) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withStatus(failure));
            assertThatThrownBy(() -> client.check(TECHNICIAN, request(UUID.randomUUID())))
                    .as(failure.toString()).isInstanceOf(EligibilityCheckException.class);
        }
        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("timeout")));
        assertThatThrownBy(() -> client.check(TECHNICIAN, request(UUID.randomUUID())))
                .isInstanceOf(EligibilityCheckException.class);
    }

    @Test
    void unaRespuestaIncompletaSeTrataComoNoVerificable() {
        UUID id = UUID.randomUUID();
        for (String body : List.of("{}", "{\"results\":null}", "{\"results\":[{\"eligible\":true}]}",
                "{\"results\":[{\"requestId\":\"" + id + "\"}]}", "{\"results\":[null]}",
                "{\"results\":[{\"requestId\":\"" + UUID.randomUUID() + "\",\"eligible\":true}]}")) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.check(TECHNICIAN, request(id)))
                    .as(body).isInstanceOf(EligibilityCheckException.class);
        }
    }
}
