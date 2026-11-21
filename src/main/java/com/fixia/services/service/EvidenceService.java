package com.fixia.services.service;

import com.fixia.services.domain.ActorRole;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceEvidence;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.dto.EvidenceResponse;
import com.fixia.services.exception.ContractNotFoundException;
import com.fixia.services.exception.EvidenceIntegrityException;
import com.fixia.services.exception.EvidenceLimitExceededException;
import com.fixia.services.exception.EvidenceNotAllowedException;
import com.fixia.services.exception.EvidenceNotFoundException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceEvidenceRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.storage.EvidenceStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Servicio de gestión de evidencias (GC-439, RF-047, RD-11).
 * Permite al técnico contratado subir evidencias del trabajo en curso o finalizado,
 * garantiza límites y validación criptográfica SHA-256, y expone su consulta y descarga segura.
 */
@Service
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

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
     *
     * <p>Subir el mismo archivo (mismo SHA-256) al mismo contrato es idempotente: devuelve la evidencia ya registrada,
     * sin un objeto ni un evento nuevos. Si la transacción no se confirma después de escribir el objeto, el objeto se
     * borra: nunca queda una referencia válida a una subida incompleta ni un objeto sin referencia.
     */
    @Transactional
    public UploadResult upload(UUID providerId, UUID contractId, String contentType, byte[] content) {
        ServiceContract contract = contractRepository.findByIdAndExecutorId(contractId, providerId)
                .orElseThrow(ContractNotFoundException::new);

        ServiceRequest request = serviceRequestRepository.findByIdForUpdate(contract.getServiceRequestId())
                .orElseThrow(ContractNotFoundException::new);

        if (request.getStatus() != ServiceRequestStatus.IN_PROGRESS &&
                request.getStatus() != ServiceRequestStatus.TECHNICIAN_FINISHED) {
            throw new EvidenceNotAllowedException(
                    "Solo se pueden subir evidencias en servicios en curso o finalizados por el técnico");
        }

        String extension = storageService.validateAndDetectExtension(contentType, content);
        String sha256 = storageService.calculateSha256(content);

        Optional<ServiceEvidence> existing = evidenceRepository.findByContractIdAndSha256(contractId, sha256);
        if (existing.isPresent()) {
            return new UploadResult(EvidenceResponse.from(existing.get()), false);
        }

        if (evidenceRepository.countByContractId(contractId) >= MAX_EVIDENCES_PER_SERVICE) {
            throw new EvidenceLimitExceededException(
                    "Se ha alcanzado el límite máximo de " + MAX_EVIDENCES_PER_SERVICE + " evidencias para este servicio");
        }

        UUID evidenceId = UUID.randomUUID();
        String objectKey = "contracts/" + contractId + "/" + evidenceId + "." + extension;

        // Se registra antes de escribir: cualquier fallo posterior (la propia escritura, la BD, el Outbox o el commit)
        // revierte la transacción y deja el objeto fuera del almacenamiento.
        deleteObjectUnlessCommitted(objectKey);
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

        ServiceEvidence saved = evidenceRepository.saveAndFlush(evidence);

        // Registrar evento transaccional en el Outbox
        events.record(
                BusinessEvents.evidenceRegistered(evidenceId, request.getId(), providerId, contentType, content.length, sha256),
                request.getId(),
                BusinessEvents.Actor.professional(providerId),
                now
        );

        return new UploadResult(EvidenceResponse.from(saved), true);
    }

    private void deleteObjectUnlessCommitted(String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storageService.deleteQuietly(objectKey);
                }
            }
        });
    }

    /**
     * Lista evidencias para el prestador contratado.
     */
    @Transactional(readOnly = true)
    public List<EvidenceResponse> listForProvider(UUID providerId, UUID contractId) {
        contractRepository.findByIdAndExecutorId(contractId, providerId)
                .orElseThrow(ContractNotFoundException::new);

        return evidenceRepository.findByContractIdOrderByCreatedAtAsc(contractId).stream()
                .map(EvidenceResponse::from)
                .toList();
    }

    /** Lista evidencias para la empresa prestadora del contrato (GC-551): solo consulta. */
    @Transactional(readOnly = true)
    public List<EvidenceResponse> listForCompany(UUID companyAdminUserId, UUID contractId) {
        contractRepository.findByIdAndProviderId(contractId, companyAdminUserId)
                .orElseThrow(ContractNotFoundException::new);
        return evidenceRepository.findByContractIdOrderByCreatedAtAsc(contractId).stream()
                .map(EvidenceResponse::from)
                .toList();
    }

    /** Descarga una evidencia para la empresa prestadora del contrato (GC-551). */
    @Transactional(readOnly = true)
    public EvidenceContent downloadForCompany(UUID companyAdminUserId, UUID contractId, UUID evidenceId) {
        contractRepository.findByIdAndProviderId(contractId, companyAdminUserId)
                .orElseThrow(ContractNotFoundException::new);
        ServiceEvidence evidence = evidenceRepository.findByIdAndContractId(evidenceId, contractId)
                .orElseThrow(EvidenceNotFoundException::new);
        return verified(evidence);
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
        contractRepository.findByIdAndExecutorId(contractId, providerId)
                .orElseThrow(ContractNotFoundException::new);

        ServiceEvidence evidence = evidenceRepository.findByIdAndContractId(evidenceId, contractId)
                .orElseThrow(EvidenceNotFoundException::new);

        return verified(evidence);
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

        return verified(evidence);
    }

    /** Entrega el archivo solo si su SHA-256 coincide con el registrado al subirlo. */
    private EvidenceContent verified(ServiceEvidence evidence) {
        byte[] bytes = storageService.download(evidence.getObjectKey());
        if (bytes.length != evidence.getSizeBytes()
                || !storageService.calculateSha256(bytes).equalsIgnoreCase(evidence.getSha256())) {
            log.error("Integridad violada en la evidencia {}: el contenido almacenado no coincide con su SHA-256",
                    evidence.getId());
            throw new EvidenceIntegrityException("El contenido almacenado no coincide con el registrado");
        }
        return new EvidenceContent(evidence, bytes);
    }

    /** {@code created} es falso cuando el archivo ya estaba registrado en el contrato. */
    public record UploadResult(EvidenceResponse evidence, boolean created) {
    }

    public record EvidenceContent(ServiceEvidence evidence, byte[] bytes) {
    }
}
