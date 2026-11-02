package com.fixia.services.client;

import com.fixia.services.config.MatchingClientProperties;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.exception.InvalidMatchingQueryException;
import com.fixia.services.exception.MatchingLookupException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Cliente de ms-matching-geo (GC-474): qué envía, cómo lee la respuesta y que falla cerrado. */
class MatchingClientTest {

    private static final String BASE_URL = "http://ms-matching-geo:8000";
    private static final String URL = BASE_URL + "/internal/matching/technicians";
    private static final Instant EXPECTED_AT = Instant.parse("2099-10-19T14:00:00Z");

    private MockRestServiceServer server;
    private MatchingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MatchingClient(builder.build(),
                new MatchingClientProperties(BASE_URL, "secreto-interno", Duration.ofSeconds(1), Duration.ofSeconds(8)));
    }

    private static ServiceRequest request(ApproximateLocation location) {
        return ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.PLUMBING, "Fuga de agua en el baño", location,
                new ExactLocation("Calle 10 # 5-20, apto 301"), EXPECTED_AT, new BigDecimal("200000"), CurrencyCode.COP,
                List.of(), Instant.now());
    }

    private static ServiceRequest withCoordinates() {
        return request(new ApproximateLocation("Bogotá", "Chapinero", 4.6533, -74.0836));
    }

    private static String technician(UUID userId) {
        return "{\"userId\":\"" + userId + "\",\"displayName\":\"Carlos G.\",\"categories\":[\"PLUMBING\"],"
                + "\"city\":\"Bogotá\"}";
    }

    @Test
    void enviaSoloLoAproximadoDeLaSolicitudConLaClaveCompartida() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "secreto-interno"))
                .andExpect(jsonPath("$.category").value("PLUMBING"))
                .andExpect(jsonPath("$.city").value("Bogotá"))
                .andExpect(jsonPath("$.latitude").value(4.65))
                .andExpect(jsonPath("$.longitude").value(-74.08))
                .andExpect(jsonPath("$.expectedAt").value("2099-10-19T14:00:00Z"))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.cursor").doesNotExist())
                .andExpect(content().string(not(containsString("Calle 10"))))
                .andRespond(withSuccess("{\"items\":[],\"nextCursor\":null}", MediaType.APPLICATION_JSON));

        client.findCompatible(withCoordinates(), 20, null);

        server.verify();
    }

    @Test
    void sinCoordenadasEnLaSolicitudNoLasEnvia() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.city").value("Bogotá"))
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist())
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        client.findCompatible(request(new ApproximateLocation("Bogotá", "Chapinero")), 20, null);

        server.verify();
    }

    @Test
    void reenviaElLimiteYElCursor() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.cursor").value("c1"))
                .andRespond(withSuccess("{\"items\":[],\"nextCursor\":\"c2\"}", MediaType.APPLICATION_JSON));

        MatchingClient.CompatibleTechnicians page = client.findCompatible(withCoordinates(), 5, "c1");

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isEqualTo("c2");
    }

    @Test
    void leeLosTecnicosIgnorandoCamposQueNoConoce() {
        UUID first = UUID.randomUUID();
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"items\":[" + technician(first)
                + ",{\"userId\":\"" + UUID.randomUUID() + "\",\"displayName\":\"Diana T.\",\"city\":\"Bogotá\","
                + "\"otro\":\"dato\"}],\"nextCursor\":\"c9\",\"extra\":1}", MediaType.APPLICATION_JSON));

        MatchingClient.CompatibleTechnicians page = client.findCompatible(withCoordinates(), 20, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).userId()).isEqualTo(first);
        assertThat(page.items().get(0).displayName()).isEqualTo("Carlos G.");
        assertThat(page.items().get(0).categories()).containsExactly("PLUMBING");
        assertThat(page.items().get(1).categories()).isEmpty();
        assertThat(page.nextCursor()).isEqualTo("c9");
    }

    @Test
    void unCursorQueMatchingRechazaEsUnaConsultaInvalida() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.findCompatible(withCoordinates(), 20, "falso"))
                .isInstanceOf(InvalidMatchingQueryException.class)
                .hasMessage("El cursor no es válido");
    }

    @Test
    void unBadRequestSinCursorEsUnFalloDeMatchingNoDelCliente() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.findCompatible(withCoordinates(), 20, null))
                .isInstanceOf(MatchingLookupException.class);
    }

    @Test
    void fallaCerradoSiMatchingNoResponde() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.INTERNAL_SERVER_ERROR,
                HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE)) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withStatus(status));
            assertThatThrownBy(() -> client.findCompatible(withCoordinates(), 20, "c1"))
                    .as("estado %s", status).isInstanceOf(MatchingLookupException.class);
        }
        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("tarda demasiado")));
        assertThatThrownBy(() -> client.findCompatible(withCoordinates(), 20, null))
                .isInstanceOf(MatchingLookupException.class);
    }

    @Test
    void fallaCerradoConUnaRespuestaInvalida() {
        for (String body : List.of("no es json", "{}", "{\"items\":null}", "{\"items\":[null]}",
                "{\"items\":[{\"displayName\":\"Sin id\"}]}")) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.findCompatible(withCoordinates(), 20, null))
                    .as("cuerpo %s", body).isInstanceOf(MatchingLookupException.class);
        }
    }
}
