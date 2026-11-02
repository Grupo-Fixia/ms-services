package com.fixia.services.repository;

import com.fixia.services.domain.ServiceEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceEvidenceRepository extends JpaRepository<ServiceEvidence, UUID> {

    List<ServiceEvidence> findByContractIdOrderByCreatedAtAsc(UUID contractId);

    List<ServiceEvidence> findByServiceRequestIdOrderByCreatedAtAsc(UUID serviceRequestId);

    long countByContractId(UUID contractId);

    Optional<ServiceEvidence> findByIdAndContractId(UUID id, UUID contractId);

    Optional<ServiceEvidence> findByIdAndServiceRequestId(UUID id, UUID serviceRequestId);
}

