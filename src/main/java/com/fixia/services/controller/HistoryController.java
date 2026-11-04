package com.fixia.services.controller;

import com.fixia.services.domain.ActorRole;
import com.fixia.services.dto.HistoryItemResponse;
import com.fixia.services.dto.HistoryPageResponse;
import com.fixia.services.exception.InvalidHistoryQueryException;
import com.fixia.services.service.HistoryCursor;
import com.fixia.services.service.HistoryService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Historial propio de servicios del cliente o del prestador autenticado (GC-448): roles {@code ROLE_CLIENT} y
 * {@code ROLE_PROFESSIONAL} (ver SecurityConfig). Quien consulta y cómo participó salen siempre del token, nunca de la
 * ruta ni de los parámetros. El detalle de cada servicio es el de las contrataciones que ya existen.
 */
@RestController
@RequestMapping("/api/services/history")
public class HistoryController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    /** El claim "sub" de los tokens de ms-users tiene la forma {@code usr_<uuid>}. */
    private static final String SUBJECT_PREFIX = "usr_";

    private static final String CLIENT_ROLE = "ROLE_CLIENT";

    private final HistoryService service;

    public HistoryController(HistoryService service) {
        this.service = service;
    }

    /**
     * Una página del historial. Los parámetros se reciben como texto para responder 400 (y no el 404 que el manejador
     * común da a un identificador mal formado) cuando no se pueden interpretar.
     */
    @GetMapping
    public HistoryPageResponse list(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                    @RequestParam(required = false) String limit,
                                    @RequestParam(required = false) String cursor) {
        UUID userId = userId(jwt);
        ActorRole role = roleOf(authentication);
        HistoryService.Page page = service.page(userId, role, parseLimit(limit), parseCursor(cursor));
        return new HistoryPageResponse(
                page.items().stream()
                        .map(item -> HistoryItemResponse.from(item.contract(), item.request(), item.role(),
                                item.timeline()))
                        .toList(),
                page.next() == null ? null : page.next().encode());
    }

    /** El cliente participa como cliente; el resto de los roles permitidos, como prestador. */
    private static ActorRole roleOf(Authentication authentication) {
        boolean client = authentication.getAuthorities().stream()
                .anyMatch(authority -> CLIENT_ROLE.equals(authority.getAuthority()));
        return client ? ActorRole.CLIENT : ActorRole.PROFESSIONAL;
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

    private static InvalidHistoryQueryException invalidLimit() {
        return new InvalidHistoryQueryException("El límite debe ser un número entre 1 y " + MAX_LIMIT);
    }

    private static HistoryCursor parseCursor(String cursor) {
        return cursor == null || cursor.isBlank() ? null : HistoryCursor.decode(cursor.strip());
    }

    /**
     * Si el "sub" no tiene el formato esperado la sesión se trata como inválida: la excepción de autenticación llega
     * al mismo 401 que cualquier otro token inválido.
     */
    private static UUID userId(Jwt jwt) {
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
