package com.fixia.services.config;

import com.fixia.services.client.TokenRevocationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Rechaza los access tokens cerrados con logout en ms-users. {@link JwtConfig} lo suma a la validación del
 * decoder, de modo que el token deja de servir de inmediato, sin esperar a su expiración.
 *
 * <p>Falla cerrado: si no se puede comprobar la revocación (ms-users caído, lento o con error) el token se
 * rechaza, porque aceptarlo haría valer un token revocado mientras dure la caída.
 */
@Component
public class TokenRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationValidator.class);

    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "Token revocado", null);
    private static final OAuth2Error EXPIRED = new OAuth2Error("invalid_token", "Token expirado", null);
    private static final OAuth2Error UNVERIFIABLE =
            new OAuth2Error("invalid_token", "No se pudo verificar la revocación del token", null);

    private final TokenRevocationClient revocationClient;
    private final Clock clock;

    public TokenRevocationValidator(TokenRevocationClient revocationClient, Clock clock) {
        this.revocationClient = revocationClient;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        // Sin "jti" no se puede comprobar la revocación: los tokens de ms-users siempre lo incluyen.
        if (jwt.getId() == null) {
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        // Un token vencido (más allá de la tolerancia de relojes) no vale de todos modos: se evita la llamada.
        if (jwt.getExpiresAt() != null
                && jwt.getExpiresAt().plus(JwtConfig.CLOCK_SKEW).isBefore(clock.instant())) {
            return OAuth2TokenValidatorResult.failure(EXPIRED);
        }
        try {
            return revocationClient.isRevoked(jwt.getId())
                    ? OAuth2TokenValidatorResult.failure(REVOKED)
                    : OAuth2TokenValidatorResult.success();
        } catch (RuntimeException e) {
            // Sin el jti ni detalles del token en el log.
            log.warn("No se pudo comprobar la revocación de un token; se rechaza la petición ({})",
                    e.getClass().getSimpleName());
            return OAuth2TokenValidatorResult.failure(UNVERIFIABLE);
        }
    }
}
