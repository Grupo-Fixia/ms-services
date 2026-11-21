package com.fixia.services.service;

import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ProviderType;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.domain.ServiceRequestTransition;
import com.fixia.services.dto.ParticipationResponse;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Participación en una solicitud para ms-users (GC-444, contrato de GC-443). Solo lee: la solicitud, la postulación
 * seleccionada (para saber si el prestador es una empresa) y el historial de transiciones (para el momento del cierre,
 * igual que el historial propio de GC-448).
 */
@Service
public class ParticipationService {

    private final ServiceRequestRepository requestRepository;
    private final ServiceRequestApplicationRepository applicationRepository;
    private final ServiceRequestTransitionRepository transitionRepository;
    private final ServiceContractRepository contractRepository;

    public ParticipationService(ServiceRequestRepository requestRepository,
                                ServiceRequestApplicationRepository applicationRepository,
                                ServiceRequestTransitionRepository transitionRepository,
                                ServiceContractRepository contractRepository) {
        this.contractRepository = contractRepository;
        this.requestRepository = requestRepository;
        this.applicationRepository = applicationRepository;
        this.transitionRepository = transitionRepository;
    }

    /**
     * @throws ServiceRequestNotFoundException si la solicitud no existe
     */
    @Transactional(readOnly = true)
    public ParticipationResponse participation(UUID serviceRequestId) {
        ServiceRequest request = requestRepository.findById(serviceRequestId)
                .orElseThrow(ServiceRequestNotFoundException::new);
        UUID providerId = request.getSelectedProviderId();
        ProviderType providerType = providerId == null ? null : selectedProviderType(request, providerId);
        Instant closedAt = request.getStatus() == ServiceRequestStatus.CLOSED ? closedAt(serviceRequestId) : null;
        return new ParticipationResponse(request.getId(), request.getStatus(), request.getClientId(), providerId,
                providerType, closedAt, executorOf(request, providerId));
    }

    /**
     * El tipo sale de la postulación seleccionada de ese prestador. Las selecciones anteriores a GC-468 son de técnicos
     * independientes (es el valor por defecto de V12).
     */
    private ProviderType selectedProviderType(ServiceRequest request, UUID providerId) {
        return applicationRepository.findByServiceRequestIdOrderByCreatedAtAscIdAsc(request.getId()).stream()
                .filter(application -> application.getStatus() == ApplicationStatus.SELECTED
                        && providerId.equals(application.getProviderId()))
                .map(ServiceRequestApplication::getProviderType)
                .findFirst()
                .orElse(ProviderType.INDEPENDENT);
    }

    /**
     * Quién ejecuta el servicio (GC-551): el del contrato si ya se contrató; si no, el técnico designado de la postulación
     * seleccionada (empresa) o el mismo prestador (independiente). Nulo si aún no hay prestador. ms-users evalúa a este
     * técnico, no a la empresa.
     */
    private UUID executorOf(ServiceRequest request, UUID providerId) {
        if (providerId == null) {
            return null;
        }
        return contractRepository.findByServiceRequestId(request.getId())
                .map(ServiceContract::getExecutorId)
                .orElseGet(() -> applicationRepository
                        .findByServiceRequestIdAndProviderId(request.getId(), providerId)
                        .filter(application -> application.getStatus() == ApplicationStatus.SELECTED
                                && application.getProviderType() == ProviderType.COMPANY)
                        .map(ServiceRequestApplication::getTechnicianId)
                        .orElse(providerId));
    }

    /** El último paso a {@code CLOSED}; {@code null} si la solicitud es anterior al historial (RD-08): no se inventa. */
    private Instant closedAt(UUID serviceRequestId) {
        Instant closedAt = null;
        for (ServiceRequestTransition transition
                : transitionRepository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(serviceRequestId)) {
            if (transition.getToStatus() == ServiceRequestStatus.CLOSED) {
                closedAt = transition.getOccurredAt();
            }
        }
        return closedAt;
    }
}
