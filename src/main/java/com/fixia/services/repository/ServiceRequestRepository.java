package com.fixia.services.repository;

import com.fixia.services.domain.ServiceRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Para escribir: toma la fila de la solicitud en exclusiva hasta el fin de la transacción, de modo que una
     * postulación (GC-342) y la selección del prestador (GC-348) no se crucen.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ServiceRequest r where r.id = :id")
    Optional<ServiceRequest> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Para que el cliente seleccione prestador (GC-348): bloquea solo si la solicitud es suya, de modo que un cliente
     * no pueda retener el bloqueo de solicitudes ajenas ni averiguar su existencia por la espera.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ServiceRequest r where r.id = :id and r.clientId = :clientId")
    Optional<ServiceRequest> findByIdAndClientIdForUpdate(@Param("id") UUID id, @Param("clientId") UUID clientId);
}
