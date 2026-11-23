package com.fixia.services.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Propiedades compartidas con ms-users: la clave interna que protege las rutas {@code /internal/**} propias de
 * ms-services. Desde GC-547 ms-services ya no llama a ms-users ni a ms-matching-geo: lo que necesita de ellos le llega por
 * estados replicados y por {@code sesion.revocada}.
 */
@Configuration
@EnableConfigurationProperties(UsersClientProperties.class)
public class UsersClientConfig {
}
