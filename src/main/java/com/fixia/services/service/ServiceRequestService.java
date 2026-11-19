package com.fixia.services.service;

import org.springframework.beans.factory.annotation.Autowired;
import com.fixia.services.domain.ProviderType;
import com.fixia.services.client.CompanyClient;
import com.fixia.services.domain.ActorRole;
import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.dto.CreateServiceRequestRequest;
import com.fixia.services.dto.ExactLocationRequest;
import com.fixia.services.exception.ApplicationNotFoundException;
import com.fixia.services.exception.InvalidServiceRequestException;
import com.fixia.services.exception.ServiceRequestNotAvailableException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ServiceRequestService {

    private final ServiceRequestRepository repository;
    private final ServiceRequestApplicationRepository applicationRepository;
    private final ServiceRequestHistory history;
    private final BusinessEventRecorder events;
    private final Clock clock;
    private final CompanyClient companyClient;

    public ServiceRequestService(ServiceRequestRepository repository,
                                 ServiceRequestApplicationRepository applicationRepository,
                                 ServiceRequestHistory history, BusinessEventRecorder events, Clock clock) {
        this(repository, applicationRepository, history, events, clock, null);
    }

    /**
     * @param companyClient para volver a comprobar, al seleccionar una empresa, que su técnico propuesto siga en ella
     *                      (GC-551); sin él (pruebas unitarias) no se comprueba
     */
    @Autowired
    public ServiceRequestService(ServiceRequestRepository repository,
                                 ServiceRequestApplicationRepository applicationRepository,
                                 ServiceRequestHistory history, BusinessEventRecorder events, Clock clock,
                                 CompanyClient companyClient) {
        this.companyClient = companyClient;
        this.repository = repository;
        this.applicationRepository = applicationRepository;
        this.history = history;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Publica una solicitud nueva del cliente, en estado PUBLISHED, y registra {@code solicitud.publicada} en la misma
     * transacción (GC-433).
     */
    @Transactional
    public ServiceRequest create(UUID clientId, CreateServiceRequestRequest request) {
        ApproximateLocation approximate = new ApproximateLocation(
                request.approximateLocation().city(), request.approximateLocation().zone(),
                request.approximateLocation().latitude(), request.approximateLocation().longitude());
        ExactLocation exact = request.exactLocation() == null ? null : new ExactLocation(
                request.exactLocation().address(), request.exactLocation().latitude(),
                request.exactLocation().longitude());

        ServiceRequest serviceRequest;
        try {
            serviceRequest = ServiceRequest.publish(clientId, request.category(), request.description(),
                    approximate, exact, request.expectedAt(), request.budgetAmount(), request.budgetCurrency(),
                    request.photoKeys(), clock.instant());
        } catch (IllegalArgumentException e) {
            // Reglas del dominio que la validación del cuerpo no cubre (por ejemplo, el momento esperado frente
            // al reloj del servicio). El mensaje es propio y no incluye datos del cliente.
            throw new InvalidServiceRequestException(e.getMessage(), e);
        }
        ServiceRequest saved = repository.save(serviceRequest);
        history.record(saved.getId(), null, ServiceRequestStatus.PUBLISHED, clientId, ActorRole.CLIENT, null,
                saved.getPublishedAt());
        events.record(BusinessEvents.requestPublished(saved), saved.getId(), BusinessEvents.Actor.client(clientId),
                saved.getPublishedAt());
        return saved;
    }

    /** Solicitud del cliente. Si no existe o es de otro cliente, la respuesta es la misma. */
    @Transactional(readOnly = true)
    public ServiceRequest get(UUID clientId, UUID id) {
        return repository.findById(id)
                .filter(request -> request.getClientId().equals(clientId))
                .orElseThrow(ServiceRequestNotFoundException::new);
    }

    /** Solicitudes del cliente, la más reciente primero. */
    @Transactional(readOnly = true)
    public List<ServiceRequest> list(UUID clientId) {
        return repository.findByClientIdOrderByPublishedAtDesc(clientId);
    }

    /**
     * Postulaciones a una solicitud del cliente (GC-347), en orden de llegada. La solicitud debe ser suya: si no
     * existe o es de otro cliente, la respuesta es la misma. Se consulta en cualquier estado de la solicitud, para
     * que el cliente siga viendo a quién eligió y a quiénes no.
     */
    @Transactional(readOnly = true)
    public List<ServiceRequestApplication> listApplicants(UUID clientId, UUID requestId) {
        get(clientId, requestId);
        return applicationRepository.findByServiceRequestIdOrderByCreatedAtAscIdAsc(requestId);
    }

    /**
     * El cliente elige un único prestador entre los postulantes de su solicitud (GC-348). En una sola transacción,
     * con la solicitud bloqueada: la solicitud pasa a {@code PROVIDER_SELECTED} con el prestador elegido, su
     * postulación a {@code SELECTED} y las demás a {@code REJECTED}. Así la decisión es todo o nada, dos selecciones
     * simultáneas dejan una sola y una postulación nueva (GC-342), que bloquea la misma fila, no se cruza con ella.
     *
     * <p>Una solicitud ajena o inexistente da la misma respuesta (404), igual que una postulación que no es de esa
     * solicitud. Si la solicitud ya no está abierta (ya tiene prestador, se canceló o venció, o pasó su momento
     * esperado) no se puede seleccionar (409), salvo que sea el reintento de una selección que ya se hizo sobre esa
     * misma postulación: entonces se devuelve el estado actual, para que repetir la petición tras perder la respuesta
     * no parezca un fallo.
     *
     * @return la solicitud ya actualizada, con las fotos cargadas para armar la respuesta fuera de la transacción
     */
    @Transactional
    public ServiceRequest selectProvider(UUID clientId, UUID requestId, UUID applicationId) {
        ServiceRequest request = repository.findByIdAndClientIdForUpdate(requestId, clientId)
                .orElseThrow(ServiceRequestNotFoundException::new);
        Instant now = clock.instant();
        if (!ServiceRequestApplication.isOpen(request, now)) {
            if (isRepeatedSelection(request, applicationId)) {
                request.getPhotoKeys();
                return request;
            }
            throw new ServiceRequestNotAvailableException("La solicitud ya no admite seleccionar prestador");
        }

        List<ServiceRequestApplication> applications =
                applicationRepository.findByServiceRequestIdOrderByCreatedAtAscIdAsc(requestId);
        ServiceRequestApplication chosen = applications.stream()
                .filter(application -> application.getId().equals(applicationId))
                .findFirst()
                .orElseThrow(ApplicationNotFoundException::new);

        if (companyClient != null && chosen.getProviderType() == ProviderType.COMPANY
                && !companyClient.isTechnicianAssociated(chosen.getProviderId(), chosen.getTechnicianId())) {
            // GC-551: el técnico se desvinculó después de la postulación; no se selecciona una oferta sin ejecutor.
            throw new ServiceRequestNotAvailableException(CompanyDesignation.LEFT_COMPANY);
        }
        List<ServiceRequestApplication> rejected = applications.stream()
                .filter(application -> application != chosen).toList();
        chosen.select(now);
        rejected.forEach(application -> application.reject(now));
        request.selectProvider(chosen.getProviderId(), now);
        history.record(request.getId(), ServiceRequestStatus.PUBLISHED, ServiceRequestStatus.PROVIDER_SELECTED,
                clientId, ActorRole.CLIENT, null, now);
        // Solo hay evento cuando la selección ocurre; el reintento de arriba no publica nada (GC-433).
        events.record(BusinessEvents.providerSelected(request, chosen, rejected, now), request.getId(),
                BusinessEvents.Actor.client(clientId), now);
        request.getPhotoKeys();
        return request;
    }

    /**
     * El cliente corrige la dirección exacta de su solicitud (GC-514), solo antes de contratar. Con la solicitud
     * bloqueada, para no cruzarse con una aceptación que la contrate al mismo tiempo. La dirección no viaja en ningún
     * evento ni en el historial (es un dato protegido, ADR-009); ajena o inexistente responde 404, contratada 409.
     */
    @Transactional
    public ServiceRequest changeExactLocation(UUID clientId, UUID requestId, ExactLocationRequest body) {
        ServiceRequest request = repository.findByIdAndClientIdForUpdate(requestId, clientId)
                .orElseThrow(ServiceRequestNotFoundException::new);
        try {
            request.changeExactLocation(new ExactLocation(body.address(), body.latitude(), body.longitude()),
                    clock.instant());
        } catch (IllegalArgumentException e) {
            throw new InvalidServiceRequestException(e.getMessage(), e);
        }
        request.getPhotoKeys();
        return request;
    }

    /**
     * El cliente cancela su solicitud (GC-515). Solo antes de contratar: desde {@code PUBLISHED} o
     * {@code PROVIDER_SELECTED}. Un servicio contratado no se cancela, así que nunca hay saldo retenido que liberar.
     *
     * <p>En una sola transacción y con la solicitud bloqueada (la misma fila que toman la postulación, la selección, la
     * cotización y su aceptación, así que no se cruza con ellas): la solicitud pasa a {@code CANCELLED}, las
     * postulaciones que seguían pendientes a {@code REJECTED}, queda la transición en el historial y se registra
     * {@code solicitud.cancelada}. Una cotización enviada ya no se puede aceptar, porque la solicitud dejó de tener
     * prestador seleccionado vigente.
     *
     * <p>Una solicitud ajena o inexistente responde 404. Repetir la cancelación de una ya cancelada devuelve el estado
     * actual sin cambiar nada ni publicar otro evento. En cualquier otro estado (contratada, en curso, cerrada,
     * vencida) responde 409.
     */
    @Transactional
    public ServiceRequest cancel(UUID clientId, UUID requestId) {
        ServiceRequest request = repository.findByIdAndClientIdForUpdate(requestId, clientId)
                .orElseThrow(ServiceRequestNotFoundException::new);
        ServiceRequestStatus previous = request.getStatus();
        if (previous == ServiceRequestStatus.CANCELLED) {
            request.getPhotoKeys();
            return request;
        }
        if (previous != ServiceRequestStatus.PUBLISHED && previous != ServiceRequestStatus.PROVIDER_SELECTED) {
            throw new ServiceRequestNotAvailableException("Solo se puede cancelar una solicitud antes de contratar");
        }

        Instant now = clock.instant();
        List<ServiceRequestApplication> rejected = applicationRepository
                .findByServiceRequestIdOrderByCreatedAtAscIdAsc(requestId).stream()
                .filter(application -> application.getStatus() == ApplicationStatus.APPLIED)
                .toList();
        rejected.forEach(application -> application.reject(now));
        request.cancel(now);
        history.record(request.getId(), previous, ServiceRequestStatus.CANCELLED, clientId, ActorRole.CLIENT, null,
                now);
        events.record(BusinessEvents.requestCancelled(request, previous, rejected, now), request.getId(),
                BusinessEvents.Actor.client(clientId), now);
        request.getPhotoKeys();
        return request;
    }

    /** La solicitud ya tiene prestador y la postulación indicada es justamente la elegida. */
    private boolean isRepeatedSelection(ServiceRequest request, UUID applicationId) {
        return request.getStatus() == ServiceRequestStatus.PROVIDER_SELECTED
                && applicationRepository.findById(applicationId)
                .filter(application -> application.getServiceRequestId().equals(request.getId()))
                .filter(application -> application.getStatus() == ApplicationStatus.SELECTED)
                .isPresent();
    }
}
