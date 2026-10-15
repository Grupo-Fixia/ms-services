package com.fixia.services.service;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.dto.CreateServiceRequestRequest;
import com.fixia.services.exception.InvalidServiceRequestException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class ServiceRequestService {

    private final ServiceRequestRepository repository;
    private final Clock clock;

    public ServiceRequestService(ServiceRequestRepository repository, Clock clock) {
        this.repository = repository;
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
}
