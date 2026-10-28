package com.fixia.services.controller;

import com.fixia.services.dto.ContractResponse;
import com.fixia.services.service.ContractService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Contratación de una solicitud del cliente autenticado (GC-359). Va bajo {@code /api/services/requests}, así que solo
 * admite {@code ROLE_CLIENT} (ver SecurityConfig).
 */
@RestController
@RequestMapping("/api/services/requests/{id}/contract")
public class ServiceRequestContractController {

    /** El claim "sub" de los tokens de ms-users tiene la forma {@code usr_<uuid>}. */
    private static final String SUBJECT_PREFIX = "usr_";

    private final ContractService service;

    public ServiceRequestContractController(ContractService service) {
        this.service = service;
    }

    @GetMapping
    public ContractResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ContractService.ContractView view = service.getForClient(clientId(jwt), id);
        return ContractResponse.from(view.contract(), view.request(), view.timeline());
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
