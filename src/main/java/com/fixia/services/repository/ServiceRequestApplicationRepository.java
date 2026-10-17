package com.fixia.services.repository;

import com.fixia.services.domain.ServiceRequestApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRequestApplicationRepository extends JpaRepository<ServiceRequestApplication, UUID> {

    /** Postulaciones del técnico, la más reciente primero. */
    List<ServiceRequestApplication> findByProviderIdOrderByCreatedAtDesc(UUID providerId);

    /** Una postulación del técnico; vacío si no existe o es de otro técnico. */
    Optional<ServiceRequestApplication> findByIdAndProviderId(UUID id, UUID providerId);

    /** Postulaciones de una solicitud en orden de llegada (para consultar postulantes y seleccionar, GC-347/348). */
    List<ServiceRequestApplication> findByServiceRequestIdOrderByCreatedAtAsc(UUID serviceRequestId);
}
