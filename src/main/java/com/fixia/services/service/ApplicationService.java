package com.fixia.services.service;

import com.fixia.services.client.CompanyClient;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.CurrencyCode;
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

import java.math.BigDecimal;
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

    /** Motivo de no elegibilidad cuando el técnico propuesto no pertenece a la empresa (GC-468). */
    static final String TECHNICIAN_NOT_ASSOCIATED = "TECHNICIAN_NOT_ASSOCIATED";

    private final ServiceRequestRepository serviceRequestRepository;
    private final ServiceRequestApplicationRepository applicationRepository;
    private final TechnicianEligibilityClient eligibilityClient;
    private final CompanyClient companyClient;
    private final BusinessEventRecorder events;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ApplicationService(ServiceRequestRepository serviceRequestRepository,
                              ServiceRequestApplicationRepository applicationRepository,
                              TechnicianEligibilityClient eligibilityClient, CompanyClient companyClient,
                              BusinessEventRecorder events, TransactionTemplate transactionTemplate, Clock clock) {
        this.serviceRequestRepository = serviceRequestRepository;
        this.applicationRepository = applicationRepository;
        this.eligibilityClient = eligibilityClient;
        this.companyClient = companyClient;
        this.events = events;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Postula al técnico a una solicitud disponible para la cual es elegible, con el precio que propone (GC-423).
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
     *   <li>En esa misma transacción se registra {@code postulacion.registrada} en el Outbox (GC-433).
     * </ol>
     */
    public ServiceRequestApplication apply(UUID providerId, UUID serviceRequestId, BigDecimal proposedAmount,
                                           CurrencyCode currency) {
        return register(serviceRequestId, providerId, null, proposedAmount, currency);
    }

    /**
     * Postula a una empresa con uno de sus técnicos como ejecutor propuesto (GC-468, RF-024, RF-082). Mismas reglas
     * que {@link #apply}, con dos diferencias: ms-users confirma primero que el técnico pertenece a esa empresa (si no,
     * 422 con {@code TECHNICIAN_NOT_ASSOCIATED}), y la elegibilidad que se evalúa es la del técnico propuesto. Una
     * empresa se postula una sola vez por solicitud (409), aunque proponga otro técnico u otro precio (RF-025).
     *
     * @param companyId users.id de la cuenta de la empresa (el {@code sub} del token)
     */
    public ServiceRequestApplication applyAsCompany(UUID companyId, UUID technicianId, UUID serviceRequestId,
                                                    BigDecimal proposedAmount, CurrencyCode currency) {
        return register(serviceRequestId, companyId, technicianId, proposedAmount, currency);
    }

    /** {@code technicianId} es {@code null} para un técnico independiente y obligatorio para una empresa. */
    private ServiceRequestApplication register(UUID serviceRequestId, UUID providerId, UUID technicianId,
                                               BigDecimal proposedAmount, CurrencyCode currency) {
        boolean company = technicianId != null;
        ServiceRequest request = serviceRequestRepository.findById(serviceRequestId)
                .orElseThrow(ServiceRequestNotFoundException::new);
        ServiceRequestApplication.requireOpen(request, clock.instant());
        rejectIfAlreadyApplied(serviceRequestId, providerId);

        if (company && !companyClient.isTechnicianAssociated(providerId, technicianId)) {
            throw new TechnicianNotEligibleException(List.of(TECHNICIAN_NOT_ASSOCIATED));
        }
        TechnicianEligibilityClient.Result eligibility =
                eligibilityClient.check(company ? technicianId : providerId, request);
        if (!eligibility.eligible()) {
            throw new TechnicianNotEligibleException(eligibility.reasons());
        }

        return transactionTemplate.execute(status -> {
            ServiceRequest locked = serviceRequestRepository.findByIdForUpdate(serviceRequestId)
                    .orElseThrow(ServiceRequestNotFoundException::new);
            ServiceRequestApplication application = company
                    ? ServiceRequestApplication.submitForCompany(locked, providerId, technicianId, proposedAmount,
                    currency, clock.instant())
                    : ServiceRequestApplication.submit(locked, providerId, proposedAmount, currency, clock.instant());
            rejectIfAlreadyApplied(serviceRequestId, providerId);
            ServiceRequestApplication saved;
            try {
                saved = applicationRepository.saveAndFlush(application);
            } catch (DataIntegrityViolationException e) {
                if (isDuplicate(e)) {
                    throw new DuplicateApplicationException(null);
                }
                throw e;
            }
            events.record(BusinessEvents.applicationRegistered(saved), serviceRequestId,
                    company ? BusinessEvents.Actor.company(providerId) : BusinessEvents.Actor.professional(providerId),
                    saved.getCreatedAt());
            return saved;
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
