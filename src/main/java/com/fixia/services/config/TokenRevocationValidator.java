package com.fixia.services.config;

import com.fixia.services.revocation.RevocationList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Rechaza los access tokens cerrados con logout en ms-users. {@link JwtConfig} lo suma a la validación del decoder.
 *
 * <p>Desde GC-547 (cero dependencias síncronas) no se pregunta a ms-users: decide la lista local de revocados que
 * alimenta {@code sesion.revocada}. Un token revocado puede servir aquí el tiempo que tarda el evento en llegar (el
 * retraso del Outbox de ms-users, ~2 s, más Kafka); el PO aceptó una ventana corta. Falla cerrado: si la lista no está
 * al día (el consumidor no confirmó con Kafka hace menos de {@code fixia.revocations.max-staleness}), el token se
 * rechaza, porque no se puede saber si fue revocado.
 */
@Component
public class TokenRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationValidator.class);

    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "Token revocado", null);
    private static final OAuth2Error EXPIRED = new OAuth2Error("invalid_token", "Token expirado", null);
    private static final OAuth2Error UNVERIFIABLE =
            new OAuth2Error("invalid_token", "No se pudo verificar la revocación del token", null);

    private final RevocationList revocationList;
    private final Clock clock;

    public TokenRevocationValidator(RevocationList revocationList, Clock clock) {
        this.revocationList = revocationList;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        // Sin "jti" no se puede comprobar la revocación: los tokens de ms-users siempre lo incluyen.
        if (jwt.getId() == null) {
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        // Un token vencido (más allá de la tolerancia de relojes) no vale de todos modos.
        if (jwt.getExpiresAt() != null
                && jwt.getExpiresAt().plus(JwtConfig.CLOCK_SKEW).isBefore(clock.instant())) {
            return OAuth2TokenValidatorResult.failure(EXPIRED);
        }
        if (revocationList.contains(jwt.getId())) {
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        if (!revocationList.isFresh()) {
            log.warn("La lista local de revocados no está al día; se rechaza la petición");
            return OAuth2TokenValidatorResult.failure(UNVERIFIABLE);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
