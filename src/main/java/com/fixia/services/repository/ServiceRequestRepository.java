package com.fixia.services.repository;

import com.fixia.services.domain.ServiceRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, UUID> {

    // open-in-view está desactivado: las fotos se cargan con la solicitud.
    @Override
    @EntityGraph(attributePaths = "photos")
    Optional<ServiceRequest> findById(UUID id);

    @EntityGraph(attributePaths = "photos")
    List<ServiceRequest> findByClientIdOrderByPublishedAtDesc(UUID clientId);
}
