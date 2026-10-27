package com.fixia.services.repository;

import com.fixia.services.domain.ServiceRequestTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceRequestTransitionRepository extends JpaRepository<ServiceRequestTransition, UUID> {

    /** Historial de la solicitud, del más antiguo al más reciente; a igual instante, por id para un orden estable. */
    List<ServiceRequestTransition> findByServiceRequestIdOrderByOccurredAtAscIdAsc(UUID serviceRequestId);
}
