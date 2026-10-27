package com.fixia.services.service;

import com.fixia.services.domain.ActorRole;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.domain.ServiceRequestTransition;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Historial de transiciones de la solicitud (GC-437). Se escribe en la misma transacción que el cambio de estado y que
 * el evento del Outbox, y con el mismo instante: si algo falla, no queda un historial de un cambio que no ocurrió. Las
 * transiciones anteriores a la tabla no se reconstruyen, así que un servicio antiguo puede tener un historial vacío o
 * parcial.
 */
@Service
public class ServiceRequestHistory {

    private final ServiceRequestTransitionRepository repository;

    public ServiceRequestHistory(ServiceRequestTransitionRepository repository) {
        this.repository = repository;
    }

    /**
     * Anota un cambio de estado. Exige una transacción en curso (la del cambio): sin ella se rechaza en vez de guardar un
     * historial sin su transición.
     *
     * @param from estado anterior; nulo en la publicación
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID serviceRequestId, ServiceRequestStatus from, ServiceRequestStatus to, UUID actorId,
                       ActorRole actorRole, String reason, Instant at) {
        repository.save(ServiceRequestTransition.of(serviceRequestId, from, to, actorId, actorRole, reason, at));
    }

    /** Historial de la solicitud, del más antiguo al más reciente. */
    @Transactional(readOnly = true)
    public List<ServiceRequestTransition> timeline(UUID serviceRequestId) {
        return repository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(serviceRequestId);
    }
}
