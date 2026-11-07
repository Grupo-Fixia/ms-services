package com.fixia.services.controller;

import com.fixia.services.dto.EvidenceResponse;
import com.fixia.services.exception.InvalidEvidenceException;
import com.fixia.services.service.EvidenceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Endpoints de evidencias para el técnico contratado (GC-439, RF-047).
 */
@RestController
@RequestMapping("/api/services/contracts/{contractId}/evidences")
public class ServiceContractEvidenceController {

    private static final String SUBJECT_PREFIX = "usr_";

    private final EvidenceService service;

    public ServiceContractEvidenceController(EvidenceService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EvidenceResponse> upload(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable UUID contractId,
                                   @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidEvidenceException("El archivo de evidencia es obligatorio");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new InvalidEvidenceException("No fue posible leer el archivo de evidencia");
        }
        String contentType = file.getContentType();
        EvidenceService.UploadResult result = service.upload(actorId(jwt), contractId, contentType, bytes);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.evidence());
    }

    @GetMapping
    public List<EvidenceResponse> list(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                       @PathVariable UUID contractId) {
        return isCompany(authentication)
                ? service.listForCompany(actorId(jwt), contractId)
                : service.listForProvider(actorId(jwt), contractId);
    }

    @GetMapping("/{evidenceId}/content")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                                        @PathVariable UUID contractId,
                                                        @PathVariable UUID evidenceId) {
        EvidenceService.EvidenceContent content = isCompany(authentication)
                ? service.downloadForCompany(actorId(jwt), contractId, evidenceId)
                : service.downloadForProvider(actorId(jwt), contractId, evidenceId);
        String filename = content.evidence().getId() + extractExtension(content.evidence().getObjectKey());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, content.evidence().getContentType())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(content.bytes().length)
                .body(content.bytes());
    }

    /** La empresa prestadora (GC-551) consulta y descarga; subir sigue siendo solo del técnico designado. */
    private static boolean isCompany(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_COMPANY".equals(authority.getAuthority()));
    }

    private static String extractExtension(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        return dot >= 0 ? objectKey.substring(dot) : "";
    }

    private static UUID actorId(Jwt jwt) {
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

