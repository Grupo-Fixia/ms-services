package com.fixia.services.controller;

import com.fixia.services.dto.OpportunityPageResponse;
import com.fixia.services.dto.OpportunityResponse;
import com.fixia.services.exception.InvalidOpportunityQueryException;
import com.fixia.services.service.OpportunityCursor;
import com.fixia.services.service.OpportunityService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Oportunidades compatibles del técnico autenticado (GC-338). Solo rol {@code ROLE_PROFESSIONAL} (ver SecurityConfig).
 * El técnico siempre sale del token, nunca de la ruta ni de los parámetros.
 */
@RestController
@RequestMapping("/api/services/opportunities")
public class OpportunityController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    /** El claim "sub" de los tokens de ms-users tiene la forma {@code usr_<uuid>}. */
    private static final String SUBJECT_PREFIX = "usr_";

    private final OpportunityService service;

    public OpportunityController(OpportunityService service) {
        this.service = service;
    }

    /**
     * Una página de oportunidades. Los parámetros se reciben como texto para responder 400 (y no el 404 que el manejador
     * común da a un identificador mal formado) cuando no se pueden interpretar.
     */
    @GetMapping
    public OpportunityPageResponse list(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(required = false) String limit,
                                        @RequestParam(required = false) String cursor) {
        UUID providerId = providerId(jwt);
        OpportunityService.Page page = service.findPage(providerId, parseLimit(limit), parseCursor(cursor));
        return new OpportunityPageResponse(
                page.items().stream().map(OpportunityResponse::from).toList(),
                page.next() == null ? null : page.next().encode());
    }

    private static int parseLimit(String limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        int value;
        try {
            value = Integer.parseInt(limit.strip());
        } catch (NumberFormatException e) {
            throw invalidLimit();
        }
        if (value < 1 || value > MAX_LIMIT) {
            throw invalidLimit();
        }
        return value;
    }

    private static InvalidOpportunityQueryException invalidLimit() {
        return new InvalidOpportunityQueryException("El límite debe ser un número entre 1 y " + MAX_LIMIT);
    }

    private static OpportunityCursor parseCursor(String cursor) {
        return cursor == null || cursor.isBlank() ? null : OpportunityCursor.decode(cursor.strip());
    }

    /**
     * Si el "sub" no tiene el formato esperado la sesión se trata como inválida: la excepción de autenticación llega
     * al mismo 401 que cualquier otro token inválido.
     */
    private static UUID providerId(Jwt jwt) {
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
