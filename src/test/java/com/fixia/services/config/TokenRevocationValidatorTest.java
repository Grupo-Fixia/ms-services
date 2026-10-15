package com.fixia.services.config;

import com.fixia.services.client.TokenRevocationCheckException;
import com.fixia.services.client.TokenRevocationClient;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TokenRevocationValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private final TokenRevocationClient client = mock(TokenRevocationClient.class);
    private final TokenRevocationValidator validator =
            new TokenRevocationValidator(client, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Jwt jwt(String jti, Instant expiresAt) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "usr_1")
                .issuedAt(NOW.minusSeconds(300));
        if (jti != null) {
            builder.jti(jti);
        }
        if (expiresAt != null) {
            builder.expiresAt(expiresAt);
        }
        return builder.build();
    }

    private static String errorOf(OAuth2TokenValidatorResult result) {
        return result.getErrors().iterator().next().getDescription();
    }

    @Test
    void unTokenNoRevocadoSeAcepta() {
        when(client.isRevoked("jti-1")).thenReturn(false);

        OAuth2TokenValidatorResult result = validator.validate(jwt("jti-1", NOW.plusSeconds(600)));

        assertThat(result.hasErrors()).isFalse();
        verify(client).isRevoked("jti-1");
    }

    @Test
    void unTokenRevocadoSeRechaza() {
        when(client.isRevoked("jti-1")).thenReturn(true);

        OAuth2TokenValidatorResult result = validator.validate(jwt("jti-1", NOW.plusSeconds(600)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(errorOf(result)).isEqualTo("Token revocado");
    }

    @Test
    void siMsUsersNoRespondeSeRechazaElTokenFailClosed() {
        when(client.isRevoked(anyString())).thenThrow(new TokenRevocationCheckException("caído"));

        OAuth2TokenValidatorResult result = validator.validate(jwt("jti-1", NOW.plusSeconds(600)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(errorOf(result)).contains("No se pudo verificar");
    }

    @Test
    void cualquierErrorInesperadoTambienRechazaElToken() {
        when(client.isRevoked(anyString())).thenThrow(new IllegalStateException("inesperado"));

        assertThat(validator.validate(jwt("jti-1", NOW.plusSeconds(600))).hasErrors()).isTrue();
    }

    @Test
    void unTokenSinJtiSeRechazaSinConsultarAMsUsers() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(null, NOW.plusSeconds(600)));

        assertThat(result.hasErrors()).isTrue();
        verifyNoInteractions(client);
    }

    @Test
    void unTokenVencidoMasAllaDeLaToleranciaSeRechazaSinConsultarAMsUsers() {
        OAuth2TokenValidatorResult result = validator.validate(jwt("jti-1", NOW.minusSeconds(60)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(errorOf(result)).isEqualTo("Token expirado");
        verifyNoInteractions(client);
    }

    @Test
    void unTokenRecienVencidoDentroDeLaToleranciaSiSeConsultaParaNoAceptarUnRevocado() {
        when(client.isRevoked("jti-1")).thenReturn(true);

        OAuth2TokenValidatorResult result = validator.validate(jwt("jti-1", NOW.minusSeconds(2)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(errorOf(result)).isEqualTo("Token revocado");
        verify(client).isRevoked("jti-1");
    }

    @Test
    void unTokenSinExpiracionSeConsultaDeTodosModos() {
        when(client.isRevoked("jti-1")).thenReturn(false);

        assertThat(validator.validate(jwt("jti-1", null)).hasErrors()).isFalse();
        verify(client).isRevoked("jti-1");
        verify(client, never()).isRevoked("otro");
    }
}
