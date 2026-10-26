package com.fixia.services.repository;

import com.fixia.services.domain.ServiceContract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Contrataciones formalizadas (GC-359). */
public interface ServiceContractRepository extends JpaRepository<ServiceContract, UUID> {

    /** La contratación de una solicitud, si ya se formalizó (a lo sumo una). */
    Optional<ServiceContract> findByServiceRequestId(UUID serviceRequestId);

    /** Contrataciones del prestador, la más reciente primero. */
    List<ServiceContract> findByProviderIdOrderByContractedAtDescIdDesc(UUID providerId);

    /** Una contratación del prestador; si es de otro, no se encuentra. */
    Optional<ServiceContract> findByIdAndProviderId(UUID id, UUID providerId);
}
