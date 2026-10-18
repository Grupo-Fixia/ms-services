package com.fixia.services.service;

import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.exception.ApplicationNotFoundException;
import com.fixia.services.exception.DuplicateApplicationException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.exception.TechnicianNotEligibleException;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Postulaciones del técnico autenticado (GC-342). El técnico siempre sale del token, nunca del cuerpo.
 */
@Service
public class ApplicationService {

    /** Restricción única (solicitud, técnico) de la migración V4. */
    static final String UNIQUE_CONSTRAINT = "uk_applications";

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
     *   <li>El técnico no puede tener ya una postulación a esa solicitud (409, GC-343). Se comprueba antes de
     *       consultar a ms-users, para no gastar la llamada.
     *   <li>ms-users decide si el técnico es elegible (422 con los motivos). Si no responde, no se postula (503).
     *       La consulta va fuera de la transacción, para no retener la base mientras se espera a ms-users.
     *   <li>Ya en la transacción, la solicitud se bloquea y se vuelve a comprobar que siga abierta y que no haya
     *       postulación previa: así una postulación no entra justo cuando el cliente selecciona prestador (GC-348) y
     *       dos peticiones simultáneas del mismo técnico (doble clic) dejan una sola. Si aun así la base rechaza el
     *       duplicado por la restricción única, se responde el mismo 409.
     * </ol>
     */
    public ServiceRequestApplication apply(UUID providerId, UUID serviceRequestId) {
        ServiceRequest request = serviceRequestRepository.findById(serviceRequestId)
                .orElseThrow(ServiceRequestNotFoundException::new);
        ServiceRequestApplication.requireOpen(request, clock.instant());
        rejectIfAlreadyApplied(serviceRequestId, providerId);

        TechnicianEligibilityClient.Result eligibility = eligibilityClient.check(providerId, request);
        if (!eligibility.eligible()) {
            throw new TechnicianNotEligibleException(eligibility.reasons());
        }

        return transactionTemplate.execute(status -> {
            ServiceRequest locked = serviceRequestRepository.findByIdForUpdate(serviceRequestId)
                    .orElseThrow(ServiceRequestNotFoundException::new);
            ServiceRequestApplication application = ServiceRequestApplication.submit(locked, providerId, clock.instant());
            rejectIfAlreadyApplied(serviceRequestId, providerId);
            try {
                return applicationRepository.saveAndFlush(application);
            } catch (DataIntegrityViolationException e) {
                if (isDuplicate(e)) {
                    throw new DuplicateApplicationException(null);
                }
                throw e;
            }
        });
    }

    private void rejectIfAlreadyApplied(UUID serviceRequestId, UUID providerId) {
        applicationRepository.findByServiceRequestIdAndProviderId(serviceRequestId, providerId).ifPresent(existing -> {
            throw new DuplicateApplicationException(existing.getId());
        });
    }

    /** Solo la violación de la restricción única se traduce a 409; cualquier otra sigue siendo un error. */
    static boolean isDuplicate(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(UNIQUE_CONSTRAINT);
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
