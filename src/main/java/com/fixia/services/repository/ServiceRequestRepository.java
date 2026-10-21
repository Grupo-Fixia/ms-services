package com.fixia.services.repository;

import com.fixia.services.domain.ServiceRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
     * Candidatas a oportunidad de un técnico (GC-337): solicitudes publicadas, con el momento esperado por delante y a
     * las que el técnico aún no se ha postulado, la más reciente primero. Es solo la preselección: si el técnico puede
     * atenderlas (categoría, zona y disponibilidad) lo decide ms-users. Sin fotos: no se mezclan con la paginación.
     */
    @Query("""
            select r from ServiceRequest r
            where r.status = com.fixia.services.domain.ServiceRequestStatus.PUBLISHED
              and r.expectedAt > :now
              and not exists (select 1 from ServiceRequestApplication a
                              where a.serviceRequestId = r.id and a.providerId = :providerId)
            order by r.publishedAt desc, r.id desc
            """)
    Slice<ServiceRequest> findOpenWithoutApplicationFrom(@Param("providerId") UUID providerId,
                                                         @Param("now") Instant now, Pageable pageable);

    /**
     * Para escribir: toma la fila de la solicitud en exclusiva hasta el fin de la transacción, de modo que una
     * postulación (GC-342) y la selección del prestador (GC-348) no se crucen.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ServiceRequest r where r.id = :id")
    Optional<ServiceRequest> findByIdForUpdate(@Param("id") UUID id);
}
