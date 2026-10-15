package com.fixia.services.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    /** Tolerancia mínima entre relojes; el valor por defecto de Spring (60 s) alarga demasiado un token vencido. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    @Bean
    public RSAPublicKey jwtPublicKey(JwtProperties properties, Environment environment) {
        if (properties.hasPublicKey()) {
            return JwtKeyLoader.fromPem(properties.publicKey());
        }
        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            log.warn("Sin JWT_PUBLIC_KEY: se usa una clave efímera (solo para los perfiles local y test); "
                    + "los tokens reales de ms-users no se podrán verificar");
            return JwtKeyLoader.generate();
        }
        throw new IllegalStateException("JWT_PUBLIC_KEY es obligatoria fuera de los perfiles local y test");
    }

    /**
     * Verifica firma RS256, expiración, emisor y audiencia. Los validadores adicionales registrados como bean
     * (la consulta de revocación a ms-users) se suman a la validación.
     */
    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey jwtPublicKey, JwtProperties properties,
                                 ObjectProvider<OAuth2TokenValidator<Jwt>> additionalValidators) {
        return buildDecoder(jwtPublicKey, properties, additionalValidators.orderedStream().toList());
    }

    static NimbusJwtDecoder buildDecoder(RSAPublicKey publicKey, JwtProperties properties,
                                         List<OAuth2TokenValidator<Jwt>> additionalValidators) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(CLOCK_SKEW));
        validators.add(new JwtIssuerValidator(properties.issuer()));
        validators.add(audienceValidator(properties.audience()));
        validators.addAll(additionalValidators);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        OAuth2Error error = new OAuth2Error("invalid_token", "Audiencia no válida", null);
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }
}
