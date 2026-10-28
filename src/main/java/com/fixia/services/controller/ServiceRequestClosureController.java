package com.fixia.services.controller;

import com.fixia.services.dto.ClosureObjectionRequest;
import com.fixia.services.dto.ContractResponse;
import com.fixia.services.service.ContractService;
import jakarta.validation.Valid;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Cierre del servicio por el cliente dueño (GC-441): confirmar que el trabajo terminó o objetarlo. Va bajo
 * {@code /api/services/requests}, así que solo admite {@code ROLE_CLIENT} (ver SecurityConfig). El cliente siempre sale
 * del token: nadie cierra ni objeta el servicio de otro. Las dos operaciones devuelven la contratación con su estado y su
 * historial, como {@code GET /requests/{id}/contract}.
 */
@RestController
@RequestMapping("/api/services/requests/{id}")
public class ServiceRequestClosureController {

    /** El claim "sub" de los tokens de ms-users tiene la forma {@code usr_<uuid>}. */
    private static final String SUBJECT_PREFIX = "usr_";

    private final ContractService service;

    public ServiceRequestClosureController(ContractService service) {
        this.service = service;
    }

    /**
     * Confirma el cierre: {@code TECHNICIAN_FINISHED -> CLOSED}, definitivo. Sin cuerpo. Confirmar un servicio ya cerrado
     * responde lo mismo (200) sin duplicar nada; si el técnico aún no lo declaró finalizado, 409.
     */
    @PostMapping("/closure")
    public ContractResponse confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ContractService.ContractView view = service.confirmClosure(clientId(jwt), id);
        return ContractResponse.from(view.contract(), view.request(), view.timeline());
    }

    /**
     * Objeta el cierre: {@code TECHNICIAN_FINISHED -> IN_PROGRESS} con el motivo (obligatorio, máximo 500 caracteres). No
     * es un cierre satisfactorio. Un servicio ya cerrado no se puede objetar (409).
     */
    @PostMapping("/closure-objection")
    public ContractResponse object(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                   @Valid @RequestBody ClosureObjectionRequest request) {
        ContractService.ContractView view = service.objectClosure(clientId(jwt), id, request.reason());
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
