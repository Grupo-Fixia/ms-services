package com.fixia.services.controller;

import com.fixia.services.dto.QuotationResponse;
import com.fixia.services.service.QuotationService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cotizaciones recibidas en una solicitud del cliente autenticado (GC-354). Va bajo {@code /api/services/requests},
 * así que solo admite {@code ROLE_CLIENT} (ver SecurityConfig). Aceptar o rechazar llega con GC-358.
 */
@RestController
@RequestMapping("/api/services/requests/{id}/quotations")
public class ServiceRequestQuotationController {

    /** El claim "sub" de los tokens de ms-users tiene la forma {@code usr_<uuid>}. */
    private static final String SUBJECT_PREFIX = "usr_";

    private final QuotationService service;

    public ServiceRequestQuotationController(QuotationService service) {
        this.service = service;
    }

    @GetMapping
    public List<QuotationResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Instant now = service.now();
        return service.listForClient(clientId(jwt), id).stream()
                .map(quotation -> QuotationResponse.from(quotation, now))
                .toList();
    }

    /**
     * Si el "sub" no tiene el formato esperado la sesión se trata como inválida: la excepción de autenticación
     * llega al mismo 401 que cualquier otro token inválido.
     */
    private static UUID clientId(Jwt jwt) {
        String subject = jwt.getSubject();
        try {
            if (subject == null || !subject.startsWith(SUBJECT_PREFIX)) {
                throw new IllegalArgumentException("sub inválido");
            }
            return UUID.fromString(subject.substring(SUBJECT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new BadCredentialsException("Sesión no válida");
        }
    }
}
