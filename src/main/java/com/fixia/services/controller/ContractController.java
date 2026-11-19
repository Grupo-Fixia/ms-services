package com.fixia.services.controller;

import com.fixia.services.dto.ContractResponse;
import com.fixia.services.service.ContractService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Contrataciones del prestador autenticado (GC-359). Solo rol {@code ROLE_PROFESSIONAL} (ver SecurityConfig). El
 * prestador siempre sale del token: nadie ve las contrataciones de otro. Es el camino por el que el prestador recibe la
 * dirección exacta de la solicitud que contrató.
 */
@RestController
@RequestMapping("/api/services/contracts")
public class ContractController {

    /** El claim "sub" de los tokens de ms-users tiene la forma {@code usr_<uuid>}. */
    private static final String SUBJECT_PREFIX = "usr_";

    private final ContractService service;

    public ContractController(ContractService service) {
        this.service = service;
    }

    @GetMapping
    public List<ContractResponse> list(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        List<ContractService.ContractView> views = isCompany(authentication)
                ? service.listForCompany(providerId(jwt))
                : service.listMine(providerId(jwt));
        return views.stream()
                .map(view -> ContractResponse.from(view.contract(), view.request(), view.timeline(), view.paymentStatus()))
                .toList();
    }

    @GetMapping("/{contractId}")
    public ContractResponse get(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                @PathVariable UUID contractId) {
        ContractService.ContractView view = isCompany(authentication)
                ? service.getForCompany(providerId(jwt), contractId)
                : service.getMine(providerId(jwt), contractId);
        return ContractResponse.from(view.contract(), view.request(), view.timeline(), view.paymentStatus());
    }

    /**
     * El prestador contratado inicia el servicio (GC-437): {@code CONTRACTED -> IN_PROGRESS}. No lleva cuerpo: el
     * prestador sale del token y la contratación, de la ruta. Repetirlo sobre un servicio ya en curso responde lo mismo
     * (200) sin cambiar nada; desde otro estado, 409. Devuelve la contratación con su estado e historial.
     */
    @PostMapping("/{contractId}/start")
    public ContractResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID contractId) {
        ContractService.ContractView view = service.start(providerId(jwt), contractId);
        return ContractResponse.from(view.contract(), view.request(), view.timeline(), view.paymentStatus());
    }

    /**
     * El prestador contratado declara que terminó (GC-441): {@code IN_PROGRESS -> TECHNICIAN_FINISHED}. Sin cuerpo y sin
     * evidencias obligatorias. No cierra el servicio: el cierre lo confirma el cliente. Repetirlo responde lo mismo (200)
     * sin cambiar nada; desde otro estado, 409.
     */
    @PostMapping("/{contractId}/finish")
    public ContractResponse finish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID contractId) {
        ContractService.ContractView view = service.finish(providerId(jwt), contractId);
        return ContractResponse.from(view.contract(), view.request(), view.timeline(), view.paymentStatus());
    }

    /**
     * La empresa (GC-551) consulta las contrataciones en que es la prestadora; el técnico, las que ejecuta. Iniciar y
     * terminar siguen siendo solo del técnico (SecurityConfig).
     */
    private static boolean isCompany(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_COMPANY".equals(authority.getAuthority()));
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
