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
     * Candidatas a oportunidad de un técnico (GC-337, GC-338): solicitudes publicadas, con el momento esperado por
     * delante y a las que el técnico aún no se ha postulado, la más reciente primero (empates por {@code id}). Es solo la
     * preselección: si el técnico puede atenderlas (categoría, zona y disponibilidad) lo decide ms-users. Sin fotos: no
     * se mezclan con la paginación. La primera página; las siguientes, con {@link #findOpenWithoutApplicationAfter}.
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
     * Las candidatas estrictamente posteriores en el orden a la clave {@code (cursorPublishedAt, cursorId)}. Paginación
     * por claves, no por desplazamiento: nada se repite ni se salta aunque lleguen solicitudes nuevas entre páginas. Es
     * un método aparte y no un parámetro nulo porque PostgreSQL no infiere el tipo de un {@code null} sin tipo.
     */
    @Query("""
            select r from ServiceRequest r
            where r.status = com.fixia.services.domain.ServiceRequestStatus.PUBLISHED
              and r.expectedAt > :now
              and not exists (select 1 from ServiceRequestApplication a
                              where a.serviceRequestId = r.id and a.providerId = :providerId)
              and (r.publishedAt < :cursorPublishedAt
                   or (r.publishedAt = :cursorPublishedAt and r.id < :cursorId))
            order by r.publishedAt desc, r.id desc
            """)
    Slice<ServiceRequest> findOpenWithoutApplicationAfter(@Param("providerId") UUID providerId,
                                                          @Param("now") Instant now,
                                                          @Param("cursorPublishedAt") Instant cursorPublishedAt,
                                                          @Param("cursorId") UUID cursorId, Pageable pageable);

    /**
     * Para escribir: toma la fila de la solicitud en exclusiva hasta el fin de la transacción, de modo que una
     * postulación (GC-342) y la selección del prestador (GC-348) no se crucen.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ServiceRequest r where r.id = :id")
    Optional<ServiceRequest> findByIdForUpdate(@Param("id") UUID id);
}
