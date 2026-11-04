package com.fixia.services.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Conexión con ms-matching-geo para sugerirle al cliente técnicos compatibles con su solicitud (GC-474). A diferencia de
 * ms-users, esta llamada no va en el camino de cada petición, pero sí es más lenta: matching puede hacer hasta tres
 * consultas seguidas a ms-users para reunir una página, así que la espera de lectura es mayor.
 *
 * @param baseUrl        URL interna de ms-matching-geo (MATCHING_BASE_URL)
 * @param internalApiKey clave compartida de las rutas {@code /internal/**} (INTERNAL_API_KEY)
 */
@ConfigurationProperties(prefix = "fixia.matching")
public record MatchingClientProperties(String baseUrl, String internalApiKey,
                                       Duration connectTimeout, Duration readTimeout) {

    public boolean hasInternalApiKey() {
        return internalApiKey != null && !internalApiKey.isBlank();
    }

    /** Evita que la clave interna aparezca en logs si el objeto se imprime. */
    @Override
    public String toString() {
        return "MatchingClientProperties[baseUrl=" + baseUrl + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
