package com.fixia.services.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Autentica las rutas internas ({@code /internal/**}) con la clave compartida entre microservicios (GC-444, mismo
 * patrón que ms-users, ADR-040). Es la misma {@code INTERNAL_API_KEY} con la que ms-services llama a ms-users. Sin
 * clave configurada no acepta ninguna petición. La comparación es en tiempo constante sobre el hash.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    public static final String AUTHORITY = "ROLE_INTERNAL";

    private final byte[] expectedDigest;

    public InternalApiKeyFilter(String apiKey) {
        this.expectedDigest = apiKey == null || apiKey.isBlank() ? null : sha256(apiKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (isValid(provided)) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    "internal-service", null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private boolean isValid(String provided) {
        return expectedDigest != null
                && provided != null
                && !provided.isEmpty()
                && MessageDigest.isEqual(expectedDigest, sha256(provided));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible", e);
        }
    }
}
