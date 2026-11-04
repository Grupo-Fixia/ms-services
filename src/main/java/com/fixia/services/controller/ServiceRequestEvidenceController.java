package com.fixia.services.controller;

import com.fixia.services.dto.EvidenceResponse;
import com.fixia.services.service.EvidenceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints de consulta y descarga de evidencias para el cliente dueño de la solicitud (GC-439, RF-047).
 */
@RestController
@RequestMapping("/api/services/requests/{serviceRequestId}/evidences")
public class ServiceRequestEvidenceController {

    private static final String SUBJECT_PREFIX = "usr_";

    private final EvidenceService service;

    public ServiceRequestEvidenceController(EvidenceService service) {
        this.service = service;
    }

    @GetMapping
    public List<EvidenceResponse> list(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable UUID serviceRequestId) {
        return service.listForClient(clientId(jwt), serviceRequestId);
    }

    @GetMapping("/{evidenceId}/content")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt,
                                                        @PathVariable UUID serviceRequestId,
                                                        @PathVariable UUID evidenceId) {
        EvidenceService.EvidenceContent content = service.downloadForClient(clientId(jwt), serviceRequestId, evidenceId);
        String filename = content.evidence().getId() + extractExtension(content.evidence().getObjectKey());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, content.evidence().getContentType())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(content.bytes().length)
                .body(content.bytes());
    }

    private static String extractExtension(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        return dot >= 0 ? objectKey.substring(dot) : "";
    }

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

