package com.fixia.services.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Rutas internas entre microservicios ({@code /internal/**}, GC-444). Cadena propia y de mayor prioridad que la de
 * usuarios ({@link SecurityConfig}), que no cambia: aquí no se acepta un token de usuario, solo la clave compartida.
 * Sin clave válida responde el mismo 401 que cualquier petición no autenticada. Traefik y el Ingress no exponen estas
 * rutas.
 */
@Configuration
public class InternalSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain internalSecurityFilterChain(HttpSecurity http, UsersClientProperties properties,
                                                           RestAuthenticationEntryPoint entryPoint,
                                                           RestAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalApiKeyFilter(properties.internalApiKey()), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(InternalApiKeyFilter.AUTHORITY))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
