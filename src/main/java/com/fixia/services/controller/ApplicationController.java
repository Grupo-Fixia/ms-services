package com.fixia.services.controller;

import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.dto.ApplicationResponse;
import com.fixia.services.dto.CreateApplicationRequest;
import com.fixia.services.service.ApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Postulaciones del técnico autenticado (GC-342). Solo rol {@code ROLE_PROFESSIONAL} (ver SecurityConfig). El
 * técnico siempre sale del token, nunca del cuerpo ni de la ruta: nadie puede postular a otro ni ver lo suyo.
 */
@RestController
@RequestMapping("/api/services/applications")
public class ApplicationController {

    /** El claim "sub" de los tokens de ms-users tiene la forma {@code usr_<uuid>}. */
    private static final String SUBJECT_PREFIX = "usr_";

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApplicationResponse> apply(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody CreateApplicationRequest request) {
        UUID providerId = providerId(jwt);
        ServiceRequestApplication created = service.apply(providerId, request.serviceRequestId(),
                request.proposedAmount(), request.currency());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        ApplicationService.ApplicationView view = service.getMine(providerId, created.getId());
        return ResponseEntity.created(location).body(ApplicationResponse.from(view.application(), view.request()));
    }

    @GetMapping
    public List<ApplicationResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.listMine(providerId(jwt)).stream()
                .map(view -> ApplicationResponse.from(view.application(), view.request()))
                .toList();
    }

    @GetMapping("/{id}")
    public ApplicationResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        ApplicationService.ApplicationView view = service.getMine(providerId(jwt), id);
        return ApplicationResponse.from(view.application(), view.request());
    }

    /**
     * Si el "sub" no tiene el formato esperado la sesión se trata como inválida: la excepción de autenticación
     * llega al mismo 401 que cualquier otro token inválido.
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
