package com.fixia.services.config;

import com.fixia.services.revocation.RevocationList;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revocación sin consultar a ms-users (GC-547): decide la lista local de revocados, y solo si está al día (≤ 5 s); si
 * no, el token se rechaza (falla cerrado).
 */
class TokenRevocationValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    /** Reloj que la prueba puede adelantar. */
    private static final class MovableClock extends Clock {
        private Instant now = NOW;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final RevocationList list = new RevocationList(clock, Duration.ofSeconds(5));
    private final TokenRevocationValidator validator = new TokenRevocationValidator(list, clock);

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
    void conLaListaAlDiaUnTokenNoRevocadoSeAcepta() {
        list.markCaughtUp();

        assertThat(validator.validate(jwt("jti-1", NOW.plusSeconds(600))).hasErrors()).isFalse();
    }

    @Test
    void unTokenQueLaListaConoceComoRevocadoSeRechaza() {
        list.markCaughtUp();
        list.add("jti-1", NOW.plusSeconds(600));

        assertThat(errorOf(validator.validate(jwt("jti-1", NOW.plusSeconds(600))))).isEqualTo("Token revocado");
    }

    @Test
    void sinConfirmacionDelConsumidorSeFallaCerrado() {
        OAuth2TokenValidatorResult result = validator.validate(jwt("jti-1", NOW.plusSeconds(600)));

        assertThat(errorOf(result)).contains("No se pudo verificar");
    }

    @Test
    void siLaListaSeAtrasaMasDeCincoSegundosSeFallaCerrado() {
        list.markCaughtUp();
        clock.advance(Duration.ofSeconds(5));
        assertThat(validator.validate(jwt("jti-1", NOW.plusSeconds(600))).hasErrors()).isFalse();

        clock.advance(Duration.ofSeconds(1));

        assertThat(errorOf(validator.validate(jwt("jti-1", NOW.plusSeconds(600))))).contains("No se pudo verificar");
    }

    @Test
    void unRevocadoSeRechazaAunqueLaListaEsteAtrasada() {
        list.add("jti-1", NOW.plusSeconds(600));

        assertThat(errorOf(validator.validate(jwt("jti-1", NOW.plusSeconds(600))))).isEqualTo("Token revocado");
    }

    @Test
    void laListaOlvidaLosTokensVencidos() {
        list.add("jti-1", NOW.plusSeconds(10));
        list.add("jti-viejo", NOW.minusSeconds(1));
        assertThat(list.contains("jti-viejo")).isFalse();

        clock.advance(Duration.ofSeconds(11));

        assertThat(list.contains("jti-1")).isFalse();
    }

    @Test
    void unTokenSinJtiSeRechaza() {
        list.markCaughtUp();

        assertThat(validator.validate(jwt(null, NOW.plusSeconds(600))).hasErrors()).isTrue();
    }

    @Test
    void unTokenVencidoMasAllaDeLaToleranciaSeRechaza() {
        list.markCaughtUp();

        assertThat(errorOf(validator.validate(jwt("jti-1", NOW.minusSeconds(60))))).isEqualTo("Token expirado");
    }

    @Test
    void unTokenRecienVencidoDentroDeLaToleranciaSeRevisaEnLaLista() {
        list.markCaughtUp();
        list.add("jti-1", NOW.plusSeconds(60));

        assertThat(errorOf(validator.validate(jwt("jti-1", NOW.minusSeconds(2))))).isEqualTo("Token revocado");
    }
}
