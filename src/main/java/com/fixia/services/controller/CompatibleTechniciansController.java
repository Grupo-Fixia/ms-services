package com.fixia.services.controller;

import com.fixia.services.dto.CompatibleTechniciansResponse;
import com.fixia.services.exception.InvalidMatchingQueryException;
import com.fixia.services.service.CompatibleTechnicianService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Técnicos compatibles con una solicitud propia (GC-474). Vive bajo {@code /api/services/requests/**}, que ya exige el
 * rol CLIENT. Misma forma de paginación por cursor que las oportunidades de los técnicos.
 */
@RestController
@RequestMapping("/api/services/requests")
public class CompatibleTechniciansController {

    private static final String SUBJECT_PREFIX = "usr_";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final CompatibleTechnicianService service;

    public CompatibleTechniciansController(CompatibleTechnicianService service) {
        this.service = service;
    }

    /**
     * Prestadores que ofrecen la categoría de la solicitud, atienden su zona y tienen una franja de disponibilidad que
     * incluye su momento esperado. Solo mientras la solicitud sigue abierta; si no, 409.
     */
    @GetMapping("/{id}/compatible-technicians")
    public CompatibleTechniciansResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                              @RequestParam(required = false) String limit,
                                              @RequestParam(required = false) String cursor) {
        String position = cursor == null || cursor.isBlank() ? null : cursor.strip();
        return CompatibleTechniciansResponse.from(service.find(clientId(jwt), id, parseLimit(limit), position));
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

    private static InvalidMatchingQueryException invalidLimit() {
        return new InvalidMatchingQueryException("El límite debe ser un número entre 1 y " + MAX_LIMIT);
    }

    /**
     * Si el "sub" no tiene el formato esperado la sesión se trata como inválida: la excepción de autenticación llega al
     * mismo 401 que cualquier otro token inválido.
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
