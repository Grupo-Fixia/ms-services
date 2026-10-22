package com.fixia.services.controller;

import com.fixia.services.domain.Quotation;
import com.fixia.services.dto.CreateQuotationRequest;
import com.fixia.services.dto.QuotationResponse;
import com.fixia.services.service.QuotationService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cotizaciones del prestador autenticado (GC-354). Solo rol {@code ROLE_PROFESSIONAL} (ver SecurityConfig). El
 * prestador siempre sale del token: nadie puede cotizar en nombre de otro ni ver lo ajeno.
 */
@RestController
@RequestMapping("/api/services/quotations")
public class QuotationController {

    /** El claim "sub" de los tokens de ms-users tiene la forma {@code usr_<uuid>}. */
    private static final String SUBJECT_PREFIX = "usr_";

    private final QuotationService service;

    public QuotationController(QuotationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<QuotationResponse> send(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody CreateQuotationRequest request) {
        Quotation created = service.send(providerId(jwt), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(QuotationResponse.from(created, service.now()));
    }

    @GetMapping
    public List<QuotationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(required = false) UUID serviceRequestId) {
        Instant now = service.now();
        return service.listMine(providerId(jwt), serviceRequestId).stream()
                .map(quotation -> QuotationResponse.from(quotation, now))
                .toList();
    }

    @GetMapping("/{quotationId}")
    public QuotationResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID quotationId) {
        return QuotationResponse.from(service.getMine(providerId(jwt), quotationId), service.now());
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
