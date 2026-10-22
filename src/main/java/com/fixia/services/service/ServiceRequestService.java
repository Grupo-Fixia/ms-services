package com.fixia.services.service;

import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.dto.CreateServiceRequestRequest;
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
    private final Clock clock;

    public ServiceRequestService(ServiceRequestRepository repository,
                                 ServiceRequestApplicationRepository applicationRepository, Clock clock) {
        this.repository = repository;
        this.applicationRepository = applicationRepository;
        this.clock = clock;
    }

    /** Publica una solicitud nueva del cliente, en estado PUBLISHED. */
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
                    approximate, exact, request.expectedAt(), request.photoKeys(), clock.instant());
        } catch (IllegalArgumentException e) {
            // Reglas del dominio que la validación del cuerpo no cubre (por ejemplo, el momento esperado frente
            // al reloj del servicio). El mensaje es propio y no incluye datos del cliente.
            throw new InvalidServiceRequestException(e.getMessage(), e);
        }
        return repository.save(serviceRequest);
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

        chosen.select(now);
        applications.stream().filter(application -> application != chosen).forEach(application -> application.reject(now));
        request.selectProvider(chosen.getProviderId(), now);
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
