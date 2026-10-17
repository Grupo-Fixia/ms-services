package com.fixia.services.service;

import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.exception.ApplicationNotFoundException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.exception.TechnicianNotEligibleException;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Postulaciones del técnico autenticado (GC-342). El técnico siempre sale del token, nunca del cuerpo.
 */
@Service
public class ApplicationService {

    private final ServiceRequestRepository serviceRequestRepository;
    private final ServiceRequestApplicationRepository applicationRepository;
    private final TechnicianEligibilityClient eligibilityClient;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ApplicationService(ServiceRequestRepository serviceRequestRepository,
                              ServiceRequestApplicationRepository applicationRepository,
                              TechnicianEligibilityClient eligibilityClient,
                              TransactionTemplate transactionTemplate, Clock clock) {
        this.serviceRequestRepository = serviceRequestRepository;
        this.applicationRepository = applicationRepository;
        this.eligibilityClient = eligibilityClient;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Postula al técnico a una solicitud disponible para la cual es elegible.
     *
     * <ol>
     *   <li>La solicitud debe existir (404) y seguir abierta: publicada y con el momento esperado por delante (409).
     *   <li>ms-users decide si el técnico es elegible (422 con los motivos). Si no responde, no se postula (503).
     *       La consulta va fuera de la transacción, para no retener la base mientras se espera a ms-users.
     *   <li>Ya en la transacción, la solicitud se bloquea y se vuelve a comprobar que siga abierta: así una
     *       postulación no entra justo cuando el cliente selecciona prestador (GC-348).
     * </ol>
     */
    public ServiceRequestApplication apply(UUID providerId, UUID serviceRequestId) {
        ServiceRequest request = serviceRequestRepository.findById(serviceRequestId)
                .orElseThrow(ServiceRequestNotFoundException::new);
        ServiceRequestApplication.requireOpen(request, clock.instant());

        TechnicianEligibilityClient.Result eligibility = eligibilityClient.check(providerId, request);
        if (!eligibility.eligible()) {
            throw new TechnicianNotEligibleException(eligibility.reasons());
        }

        return transactionTemplate.execute(status -> {
            ServiceRequest locked = serviceRequestRepository.findByIdForUpdate(serviceRequestId)
                    .orElseThrow(ServiceRequestNotFoundException::new);
            return applicationRepository.saveAndFlush(
                    ServiceRequestApplication.submit(locked, providerId, clock.instant()));
        });
    }

    /** Postulaciones del técnico con la solicitud de cada una, la más reciente primero. */
    @Transactional(readOnly = true)
    public List<ApplicationView> listMine(UUID providerId) {
        List<ServiceRequestApplication> applications = applicationRepository.findByProviderIdOrderByCreatedAtDesc(providerId);
        Map<UUID, ServiceRequest> requests = serviceRequestRepository
                .findAllById(applications.stream().map(ServiceRequestApplication::getServiceRequestId).toList())
                .stream().collect(Collectors.toMap(ServiceRequest::getId, Function.identity()));
        return applications.stream()
                .map(application -> new ApplicationView(application, requests.get(application.getServiceRequestId())))
                .toList();
    }

    /** Una postulación del técnico. Si no existe o es de otro técnico, la respuesta es la misma (404). */
    @Transactional(readOnly = true)
    public ApplicationView getMine(UUID providerId, UUID applicationId) {
        ServiceRequestApplication application = applicationRepository.findByIdAndProviderId(applicationId, providerId)
                .orElseThrow(ApplicationNotFoundException::new);
        return new ApplicationView(application,
                serviceRequestRepository.findById(application.getServiceRequestId()).orElse(null));
    }

    /** Una postulación junto con su solicitud, para armar la respuesta sin volver a la base. */
    public record ApplicationView(ServiceRequestApplication application, ServiceRequest request) {
    }
}
