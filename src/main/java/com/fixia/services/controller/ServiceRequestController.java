package com.fixia.services.controller;

import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.dto.ApplicantResponse;
import com.fixia.services.dto.CreateServiceRequestRequest;
import com.fixia.services.dto.ServiceRequestResponse;
import com.fixia.services.service.ServiceRequestService;
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
 * Solicitudes de servicio del cliente autenticado (GC-296). Solo rol {@code ROLE_CLIENT} (ver SecurityConfig).
 * El cliente siempre sale del token, nunca del cuerpo ni de la ruta: nadie puede crear ni ver las de otro.
 */
@RestController
@RequestMapping("/api/services/requests")
public class ServiceRequestController {

    /** El claim "sub" de los tokens de ms-users tiene la forma {@code usr_<uuid>}. */
    private static final String SUBJECT_PREFIX = "usr_";

    private final ServiceRequestService service;

    public ServiceRequestController(ServiceRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ServiceRequestResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                         @Valid @RequestBody CreateServiceRequestRequest request) {
        ServiceRequest created = service.create(clientId(jwt), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(ServiceRequestResponse.from(created));
    }

    @GetMapping
    public List<ServiceRequestResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(clientId(jwt)).stream().map(ServiceRequestResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ServiceRequestResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ServiceRequestResponse.from(service.get(clientId(jwt), id));
    }

    /** Prestadores postulados a una solicitud propia y el estado de cada postulación (GC-347). */
    @GetMapping("/{id}/applications")
    public List<ApplicantResponse> applicants(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listApplicants(clientId(jwt), id).stream().map(ApplicantResponse::from).toList();
    }

    /**
     * Elige al prestador de la solicitud entre sus postulantes (GC-348). Devuelve la solicitud ya en
     * {@code PROVIDER_SELECTED}; los estados de las postulaciones se consultan en {@code /applications}.
     */
    @PostMapping("/{id}/applications/{applicationId}/selection")
    public ServiceRequestResponse selectProvider(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                 @PathVariable UUID applicationId) {
        return ServiceRequestResponse.from(service.selectProvider(clientId(jwt), id, applicationId));
    }

    /**
     * Si el "sub" no tiene el formato esperado la sesión se trata como inválida: la excepción de
     * autenticación llega al mismo 401 que cualquier otro token inválido.
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
