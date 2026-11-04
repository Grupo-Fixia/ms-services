package com.fixia.services.repository;

import com.fixia.services.domain.ServiceRequestTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRequestTransitionRepository extends JpaRepository<ServiceRequestTransition, UUID> {

    /** Historial de la solicitud, del más antiguo al más reciente; a igual instante, por id para un orden estable. */
    List<ServiceRequestTransition> findByServiceRequestIdOrderByOccurredAtAscIdAsc(UUID serviceRequestId);

    /** Historial de varias solicitudes a la vez, cada una del más antiguo al más reciente (GC-448: una consulta por página). */
    List<ServiceRequestTransition> findByServiceRequestIdInOrderByOccurredAtAscIdAsc(Collection<UUID> serviceRequestIds);

    /** El último cambio de la solicitud; a igual instante, el de mayor id para que el orden sea el mismo de arriba. */
    Optional<ServiceRequestTransition> findFirstByServiceRequestIdOrderByOccurredAtDescIdDesc(UUID serviceRequestId);
}
