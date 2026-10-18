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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TokenRevocationClientTest {

    private static final String BASE_URL = "http://ms-users:8080";
    private static final String STATUS_URL = BASE_URL + "/internal/tokens/abc-123/status";

    private MockRestServiceServer server;
    private TokenRevocationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = clientWith(builder.build(), "secreto-interno");
    }

    private static TokenRevocationClient clientWith(RestClient restClient, String apiKey) {
        return new TokenRevocationClient(restClient,
                new UsersClientProperties(BASE_URL, apiKey, Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    @Test
    void consultaLaRutaInternaConLaClaveCompartida() {
        server.expect(requestTo(STATUS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Api-Key", "secreto-interno"))
                .andRespond(withSuccess("{\"revoked\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.isRevoked("abc-123")).isFalse();
        server.verify();
    }

    @Test
    void informaElTokenComoRevocado() {
        server.expect(requestTo(STATUS_URL))
                .andRespond(withSuccess("{\"revoked\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.isRevoked("abc-123")).isTrue();
    }

    @Test
    void ignoraCamposAdicionalesDeLaRespuesta() {
        server.expect(requestTo(STATUS_URL))
                .andRespond(withSuccess("{\"revoked\":true,\"otro\":\"dato\"}", MediaType.APPLICATION_JSON));

        assertThat(client.isRevoked("abc-123")).isTrue();
    }

    @Test
    void unErrorHttpDeMsUsersEsUnaVerificacionFallida() {
        for (HttpStatus failure : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND,
                HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE}) {
            server.reset();
            server.expect(requestTo(STATUS_URL)).andRespond(withStatus(failure));

            assertThatThrownBy(() -> client.isRevoked("abc-123"))
                    .as(failure.toString())
                    .isInstanceOf(TokenRevocationCheckException.class);
        }
    }

    @Test
    void unTimeoutOUnaConexionCaidaEsUnaVerificacionFallida() {
        server.expect(requestTo(STATUS_URL)).andRespond(withException(new SocketTimeoutException("timeout")));

        assertThatThrownBy(() -> client.isRevoked("abc-123"))
                .isInstanceOf(TokenRevocationCheckException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void unaRespuestaSinElEstadoEsUnaVerificacionFallida() {
        for (String body : new String[]{"{}", "{\"revoked\":null}", ""}) {
            server.reset();
            server.expect(requestTo(STATUS_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.isRevoked("abc-123"))
                    .as("cuerpo: '%s'", body)
                    .isInstanceOf(TokenRevocationCheckException.class);
        }
    }

    @Test
    void unaRespuestaQueNoEsJsonEsUnaVerificacionFallida() {
        server.expect(requestTo(STATUS_URL)).andRespond(withSuccess("<html>", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.isRevoked("abc-123"))
                .isInstanceOf(TokenRevocationCheckException.class);
    }

    @Test
    void sinClaveConfiguradaEnviaLaCabeceraVaciaYMsUsersLaRechaza() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer noKeyServer = MockRestServiceServer.bindTo(builder).build();
        TokenRevocationClient noKeyClient = clientWith(builder.build(), null);
        noKeyServer.expect(requestTo(STATUS_URL))
                .andExpect(header("X-Internal-Api-Key", ""))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> noKeyClient.isRevoked("abc-123"))
                .isInstanceOf(TokenRevocationCheckException.class);
    }

    @Test
    void laClaveInternaNoApareceEnElToStringDeLaConfiguracion() {
        assertThat(new UsersClientProperties(BASE_URL, "secreto-interno", Duration.ofSeconds(1),
                Duration.ofSeconds(2)).toString()).doesNotContain("secreto-interno");
    }
}
