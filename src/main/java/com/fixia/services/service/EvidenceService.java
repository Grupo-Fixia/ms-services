package com.fixia.services.service;

import com.fixia.services.domain.ActorRole;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceEvidence;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.dto.EvidenceResponse;
import com.fixia.services.exception.ContractNotFoundException;
import com.fixia.services.exception.EvidenceLimitExceededException;
import com.fixia.services.exception.EvidenceNotAllowedException;
import com.fixia.services.exception.EvidenceNotFoundException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceEvidenceRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.storage.EvidenceStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Servicio de gestión de evidencias (GC-439, RF-047, RD-11).
 * Permite al técnico contratado subir evidencias del trabajo en curso o finalizado,
 * garantiza límites y validación criptográfica SHA-256, y expone su consulta y descarga segura.
 */
@Service
public class EvidenceService {

    public static final int MAX_EVIDENCES_PER_SERVICE = 10;

    private final ServiceEvidenceRepository evidenceRepository;
    private final ServiceContractRepository contractRepository;
    private final ServiceRequestRepository serviceRequestRepository;
    private final EvidenceStorageService storageService;
    private final BusinessEventRecorder events;
    private final Clock clock;

    public EvidenceService(ServiceEvidenceRepository evidenceRepository,
                           ServiceContractRepository contractRepository,
                           ServiceRequestRepository serviceRequestRepository,
                           EvidenceStorageService storageService,
                           BusinessEventRecorder events,
                           Clock clock) {
        this.evidenceRepository = evidenceRepository;
        this.contractRepository = contractRepository;
        this.serviceRequestRepository = serviceRequestRepository;
        this.storageService = storageService;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Sube una evidencia para la contratación indicada.
     * Solo el prestador contratado y en estados IN_PROGRESS o TECHNICIAN_FINISHED puede subir evidencias.
     */
    @Transactional
    public EvidenceResponse upload(UUID providerId, UUID contractId, String contentType, byte[] content) {
        ServiceContract contract = contractRepository.findByIdAndProviderId(contractId, providerId)
                .orElseThrow(ContractNotFoundException::new);

        ServiceRequest request = serviceRequestRepository.findByIdForUpdate(contract.getServiceRequestId())
                .orElseThrow(ContractNotFoundException::new);

        if (request.getStatus() != ServiceRequestStatus.IN_PROGRESS &&
                request.getStatus() != ServiceRequestStatus.TECHNICIAN_FINISHED) {
            throw new EvidenceNotAllowedException(
                    "Solo se pueden subir evidencias en servicios en curso o finalizados por el técnico");
        }

        if (evidenceRepository.countByContractId(contractId) >= MAX_EVIDENCES_PER_SERVICE) {
            throw new EvidenceLimitExceededException(
                    "Se ha alcanzado el límite máximo de " + MAX_EVIDENCES_PER_SERVICE + " evidencias para este servicio");
        }

        String extension = storageService.validateAndDetectExtension(contentType, content);
        String sha256 = storageService.calculateSha256(content);

        UUID evidenceId = UUID.randomUUID();
        String objectKey = "contracts/" + contractId + "/" + evidenceId + "." + extension;

        // Escribir en almacenamiento de objetos (RustFS / S3)
        storageService.upload(objectKey, contentType, content);

        Instant now = clock.instant();
        ServiceEvidence evidence = ServiceEvidence.available(
                evidenceId,
                request.getId(),
                contractId,
                providerId,
                objectKey,
                contentType,
                content.length,
                sha256,
                now
        );

        ServiceEvidence saved = evidenceRepository.save(evidence);

        // Registrar evento transaccional en el Outbox
        events.record(
                BusinessEvents.evidenceRegistered(evidenceId, request.getId(), providerId, contentType, content.length, sha256),
                request.getId(),
                BusinessEvents.Actor.professional(providerId),
                now
        );

        return EvidenceResponse.from(saved);
    }

    /**
     * Lista evidencias para el prestador contratado.
     */
    @Transactional(readOnly = true)
    public List<EvidenceResponse> listForProvider(UUID providerId, UUID contractId) {
        contractRepository.findByIdAndProviderId(contractId, providerId)
                .orElseThrow(ContractNotFoundException::new);

        return evidenceRepository.findByContractIdOrderByCreatedAtAsc(contractId).stream()
                .map(EvidenceResponse::from)
                .toList();
    }

    /**
     * Lista evidencias para el cliente dueño de la solicitud.
     */
    @Transactional(readOnly = true)
    public List<EvidenceResponse> listForClient(UUID clientId, UUID serviceRequestId) {
        serviceRequestRepository.findById(serviceRequestId)
                .filter(r -> r.getClientId().equals(clientId))
                .orElseThrow(ServiceRequestNotFoundException::new);

        return evidenceRepository.findByServiceRequestIdOrderByCreatedAtAsc(serviceRequestId).stream()
                .map(EvidenceResponse::from)
                .toList();
    }

    /**
     * Descarga el archivo de evidencia para el prestador contratado.
     */
    @Transactional(readOnly = true)
    public EvidenceContent downloadForProvider(UUID providerId, UUID contractId, UUID evidenceId) {
        contractRepository.findByIdAndProviderId(contractId, providerId)
                .orElseThrow(ContractNotFoundException::new);

        ServiceEvidence evidence = evidenceRepository.findByIdAndContractId(evidenceId, contractId)
                .orElseThrow(EvidenceNotFoundException::new);

        ResponseInputStream<GetObjectResponse> stream = storageService.download(evidence.getObjectKey());
        return new EvidenceContent(evidence, stream);
    }

    /**
     * Descarga el archivo de evidencia para el cliente dueño de la solicitud.
     */
    @Transactional(readOnly = true)
    public EvidenceContent downloadForClient(UUID clientId, UUID serviceRequestId, UUID evidenceId) {
        serviceRequestRepository.findById(serviceRequestId)
                .filter(r -> r.getClientId().equals(clientId))
                .orElseThrow(ServiceRequestNotFoundException::new);

        ServiceEvidence evidence = evidenceRepository.findByIdAndServiceRequestId(evidenceId, serviceRequestId)
                .orElseThrow(EvidenceNotFoundException::new);

        ResponseInputStream<GetObjectResponse> stream = storageService.download(evidence.getObjectKey());
        return new EvidenceContent(evidence, stream);
    }

    public record EvidenceContent(ServiceEvidence evidence, ResponseInputStream<GetObjectResponse> stream) {
    }
}

