package com.fixia.services.service;

import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ProviderType;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.dto.CompanyExecutorWorkloadResponse;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Trabajo de un técnico con su empresa, para que ms-users decida si puede desvincularse (GC-524, contrato de empresas
 * §6.1). Solo lee las postulaciones de la empresa en las que ese técnico es el propuesto.
 *
 * <ul>
 *   <li><b>Pendiente</b>: postulación {@code APPLIED} con la solicitud aún {@code PUBLISHED}, o postulación
 *   {@code SELECTED} con la solicitud seleccionada, contratada, en curso o terminada por el técnico.</li>
 *   <li><b>Todas</b>: las solicitudes en que fue el seleccionado, en cualquier estado, para preguntar por el dinero.</li>
 * </ul>
 */
@Service
public class CompanyExecutorWorkloadService {

    /** Estados en los que el servicio seleccionado todavía tiene trabajo del técnico. */
    static final Set<ServiceRequestStatus> SELECTED_OPEN = EnumSet.of(ServiceRequestStatus.PROVIDER_SELECTED,
            ServiceRequestStatus.CONTRACTED, ServiceRequestStatus.IN_PROGRESS, ServiceRequestStatus.TECHNICIAN_FINISHED);

    private final ServiceRequestApplicationRepository applicationRepository;
    private final ServiceRequestRepository requestRepository;

    public CompanyExecutorWorkloadService(ServiceRequestApplicationRepository applicationRepository,
                                          ServiceRequestRepository requestRepository) {
        this.applicationRepository = applicationRepository;
        this.requestRepository = requestRepository;
    }

    @Transactional(readOnly = true)
    public CompanyExecutorWorkloadResponse workload(UUID companyAdminUserId, UUID technicianUserId) {
        List<ServiceRequestApplication> applications = applicationRepository
                .findByProviderIdAndTechnicianIdAndProviderTypeOrderByCreatedAtAscIdAsc(companyAdminUserId,
                        technicianUserId, ProviderType.COMPANY);
        Map<UUID, ServiceRequest> requests = requestRepository
                .findAllById(applications.stream().map(ServiceRequestApplication::getServiceRequestId).toList())
                .stream()
                .collect(Collectors.toMap(ServiceRequest::getId, Function.identity()));

        List<UUID> pending = applications.stream()
                .filter(application -> isPending(application, requests.get(application.getServiceRequestId())))
                .map(ServiceRequestApplication::getServiceRequestId)
                .distinct()
                .toList();
        List<UUID> selected = applications.stream()
                .filter(application -> application.getStatus() == ApplicationStatus.SELECTED)
                .map(ServiceRequestApplication::getServiceRequestId)
                .distinct()
                .toList();
        return new CompanyExecutorWorkloadResponse(pending, selected);
    }

    private static boolean isPending(ServiceRequestApplication application, ServiceRequest request) {
        if (request == null) {
            return false;
        }
        return switch (application.getStatus()) {
            case APPLIED -> request.getStatus() == ServiceRequestStatus.PUBLISHED;
            case SELECTED -> SELECTED_OPEN.contains(request.getStatus());
            case REJECTED -> false;
        };
    }
}
