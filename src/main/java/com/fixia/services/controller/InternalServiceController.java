package com.fixia.services.controller;

import com.fixia.services.dto.ParticipationResponse;
import com.fixia.services.service.ParticipationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Consultas de otros microservicios a ms-services (GC-444). Solo con la clave interna ({@link
 * com.fixia.services.config.InternalSecurityConfig}); no pasan por el gateway.
 */
@RestController
@RequestMapping("/internal/services")
public class InternalServiceController {

    private final ParticipationService participationService;

    public InternalServiceController(ParticipationService participationService) {
        this.participationService = participationService;
    }

    /**
     * Participación y cierre de una solicitud, para que ms-users verifique una evaluación (contrato de GC-443). 404 si
     * no existe o el id está mal formado. No se cachea: un cierre posterior debe verse en la consulta siguiente.
     */
    @GetMapping("/{serviceRequestId}/participation")
    public ResponseEntity<ParticipationResponse> participation(@PathVariable UUID serviceRequestId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(participationService.participation(serviceRequestId));
    }
}
