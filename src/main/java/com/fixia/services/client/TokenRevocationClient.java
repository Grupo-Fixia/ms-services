package com.fixia.services.client;

import com.fixia.services.config.UsersClientProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Consulta a ms-users si un access token fue revocado (cierre de sesión). La lista de revocados vive en la
 * base de ms-users (Database per Service), por eso se consulta por HTTP en su ruta interna
 * {@code GET /internal/tokens/{jti}/status}, autenticada con la clave compartida.
 */
@Component
public class TokenRevocationClient {

    static final String API_KEY_HEADER = "X-Internal-Api-Key";

    /** Respuesta de ms-users. Solo se lee {@code revoked}; cualquier campo adicional se ignora. */
    record TokenStatus(Boolean revoked) {
    }

    private final RestClient restClient;
    private final UsersClientProperties properties;

    public TokenRevocationClient(@Qualifier("usersRestClient") RestClient restClient,
                                 UsersClientProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * @return {@code true} si ms-users informa el token como revocado
     * @throws TokenRevocationCheckException si no se pudo obtener una respuesta válida de ms-users
     */
    public boolean isRevoked(String jti) {
        TokenStatus status;
        try {
            status = restClient.get()
                    .uri("/internal/tokens/{jti}/status", jti)
                    .header(API_KEY_HEADER, properties.internalApiKey() == null ? "" : properties.internalApiKey())
                    .retrieve()
                    .body(TokenStatus.class);
        } catch (RestClientException e) {
            throw new TokenRevocationCheckException("ms-users no pudo confirmar el estado del token", e);
        }
        if (status == null || status.revoked() == null) {
            throw new TokenRevocationCheckException("ms-users respondió sin el estado del token");
        }
        return status.revoked();
    }
}
