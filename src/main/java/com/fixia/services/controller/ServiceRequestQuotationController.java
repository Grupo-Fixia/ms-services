package com.fixia.services.controller;

import com.fixia.services.domain.Quotation;
import com.fixia.services.dto.QuotationResponse;
import com.fixia.services.service.QuotationService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cotizaciones recibidas en una solicitud del cliente autenticado (GC-354), que además las acepta o rechaza (GC-358).
 * Va bajo {@code /api/services/requests}, así que solo admite {@code ROLE_CLIENT} (ver SecurityConfig).
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

    /** Acepta la cotización: 200 con ella ya en {@code ACCEPTED}; repetir devuelve lo mismo (idempotente). */
    @PostMapping("/{quotationId}/acceptance")
    public QuotationResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @PathVariable UUID quotationId) {
        Quotation accepted = service.accept(clientId(jwt), id, quotationId);
        return QuotationResponse.from(accepted, service.now());
    }

    /** Rechaza la cotización: 200 con ella ya en {@code REJECTED}; repetir devuelve lo mismo (idempotente). */
    @PostMapping("/{quotationId}/rejection")
    public QuotationResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @PathVariable UUID quotationId) {
        Quotation rejected = service.reject(clientId(jwt), id, quotationId);
        return QuotationResponse.from(rejected, service.now());
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
