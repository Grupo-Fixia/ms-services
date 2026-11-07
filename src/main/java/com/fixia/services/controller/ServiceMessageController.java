package com.fixia.services.controller;

import com.fixia.services.domain.ServiceContract;
import com.fixia.services.dto.MessagePageResponse;
import com.fixia.services.dto.MessageResponse;
import com.fixia.services.dto.SendMessageRequest;
import com.fixia.services.service.MessageService;
import com.fixia.services.service.MessageWaiter;
import org.springframework.web.context.request.async.DeferredResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Chat de un servicio contratado (GC-527, {@code docs/contracts/mensajeria.md}). Dos puertas a la misma conversación:
 * el técnico entra por su contratación ({@code /contracts/{id}/messages}, {@code ROLE_PROFESSIONAL}) y el cliente por
 * su solicitud ({@code /requests/{id}/messages}, {@code ROLE_CLIENT}). Quien consulta sale siempre del token. Una
 * empresa no lee ni escribe (403, SecurityConfig): la conversación es del cliente y del técnico que ejecuta. Las
 * lecturas admiten {@code wait} (long polling, {@link MessageWaiter}): sin mensajes nuevos, esperan hasta 25 s.
 */
@RestController
public class ServiceMessageController {

    private static final String SUBJECT_PREFIX = "usr_";

    private final MessageService service;
    private final MessageWaiter waiter;

    public ServiceMessageController(MessageService service, MessageWaiter waiter) {
        this.service = service;
        this.waiter = waiter;
    }

    @GetMapping("/api/services/contracts/{contractId}/messages")
    public DeferredResult<MessagePageResponse> technicianMessages(@AuthenticationPrincipal Jwt jwt,
                                                                  @PathVariable UUID contractId,
                                                                  @RequestParam(required = false) String after,
                                                                  @RequestParam(required = false) Integer limit,
                                                                  @RequestParam(required = false) Integer wait) {
        UUID technician = userId(jwt);
        ServiceContract contract = service.contractOfTechnician(technician, contractId);
        return waiter.await(contract, after, limit, wait, technician);
    }

    @PostMapping("/api/services/contracts/{contractId}/messages")
    public ResponseEntity<MessageResponse> technicianSend(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable UUID contractId,
                                                          @Valid @RequestBody SendMessageRequest body) {
        UUID technician = userId(jwt);
        return sent(service.send(service.contractOfTechnician(technician, contractId), technician, body.body(),
                body.clientMessageId()), technician);
    }

    @GetMapping("/api/services/requests/{serviceRequestId}/messages")
    public DeferredResult<MessagePageResponse> clientMessages(@AuthenticationPrincipal Jwt jwt,
                                                              @PathVariable UUID serviceRequestId,
                                                              @RequestParam(required = false) String after,
                                                              @RequestParam(required = false) Integer limit,
                                                              @RequestParam(required = false) Integer wait) {
        UUID client = userId(jwt);
        ServiceContract contract = service.contractOfClient(client, serviceRequestId);
        return waiter.await(contract, after, limit, wait, client);
    }

    @PostMapping("/api/services/requests/{serviceRequestId}/messages")
    public ResponseEntity<MessageResponse> clientSend(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable UUID serviceRequestId,
                                                      @Valid @RequestBody SendMessageRequest body) {
        UUID client = userId(jwt);
        return sent(service.send(service.contractOfClient(client, serviceRequestId), client, body.body(),
                body.clientMessageId()), client);
    }

    private static ResponseEntity<MessageResponse> sent(MessageService.SendResult result, UUID viewer) {
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(MessageResponse.from(result.message(), viewer));
    }

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
