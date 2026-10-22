package com.fixia.services.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validación de los tokens emitidos por ms-users. Este servicio solo verifica: necesita la clave pública
 * (JWT_PUBLIC_KEY, PEM X.509) y nunca la privada.
 */
@ConfigurationProperties(prefix = "fixia.jwt")
public record JwtProperties(String issuer, String audience, String publicKey) {

    boolean hasPublicKey() {
        return publicKey != null && !publicKey.isBlank();
    }
}
