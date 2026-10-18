package com.fixia.services.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Conexión con ms-users para consultar la revocación de tokens. La llamada va en el camino de cada petición
 * autenticada, por eso los tiempos de espera son cortos.
 *
 * @param baseUrl        URL interna de ms-users (USERS_BASE_URL)
 * @param internalApiKey clave compartida de las rutas {@code /internal/**} (INTERNAL_API_KEY)
 */
@ConfigurationProperties(prefix = "fixia.users")
public record UsersClientProperties(String baseUrl, String internalApiKey,
                                    Duration connectTimeout, Duration readTimeout) {

    public boolean hasInternalApiKey() {
        return internalApiKey != null && !internalApiKey.isBlank();
    }

    /** Evita que la clave interna aparezca en logs si el objeto se imprime. */
    @Override
    public String toString() {
        return "UsersClientProperties[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
