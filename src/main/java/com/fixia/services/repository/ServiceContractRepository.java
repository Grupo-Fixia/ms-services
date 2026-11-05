package com.fixia.services.repository;

import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequestStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Contrataciones formalizadas (GC-359). */
public interface ServiceContractRepository extends JpaRepository<ServiceContract, UUID> {

    /** La contratación de una solicitud, si ya se formalizó (a lo sumo una). */
    Optional<ServiceContract> findByServiceRequestId(UUID serviceRequestId);

    /** Contrataciones del prestador, la más reciente primero. */
    List<ServiceContract> findByProviderIdOrderByContractedAtDescIdDesc(UUID providerId);

    /**
     * Contrataciones del prestador cuya solicitud sigue en alguno de {@code statuses} y cuya fecha acordada cae
     * estrictamente entre {@code from} y {@code to}, sin contar la solicitud {@code excludedRequestId}. La usa
     * {@code ProviderScheduleGuard} para no contratar dos servicios del mismo prestador a la misma hora.
     */
    @Query("""
            select c from ServiceContract c, ServiceRequest r
            where r.id = c.serviceRequestId
              and c.providerId = :providerId
              and r.status in :statuses
              and c.agreedAt > :from and c.agreedAt < :to
              and c.serviceRequestId <> :excludedRequestId
            order by c.agreedAt asc
            """)
    List<ServiceContract> findBusyOverlaps(@Param("providerId") UUID providerId,
                                           @Param("statuses") Collection<ServiceRequestStatus> statuses,
                                           @Param("from") Instant from, @Param("to") Instant to,
                                           @Param("excludedRequestId") UUID excludedRequestId, Pageable pageable);

    /** El primer choque de {@link #findBusyOverlaps}, si lo hay. */
    default Optional<ServiceContract> findFirstBusyOverlap(UUID providerId, Collection<ServiceRequestStatus> statuses,
                                                           Instant from, Instant to, UUID excludedRequestId) {
        return findBusyOverlaps(providerId, statuses, from, to, excludedRequestId, Pageable.ofSize(1))
                .stream().findFirst();
    }

    /** Una contratación del prestador; si es de otro, no se encuentra. */
    Optional<ServiceContract> findByIdAndProviderId(UUID id, UUID providerId);

    /**
     * Historial del prestador (GC-448): sus contrataciones, la más reciente primero (empates por {@code id}). Es la
     * primera página; las siguientes, con {@link #findProviderHistoryAfter}.
     */
    @Query("""
            select c from ServiceContract c
            where c.providerId = :providerId
            order by c.contractedAt desc, c.id desc
            """)
    Slice<ServiceContract> findProviderHistoryFrom(@Param("providerId") UUID providerId, Pageable pageable);

    /**
     * Las contrataciones del prestador estrictamente posteriores en el orden a la clave
     * {@code (cursorContractedAt, cursorId)}. Paginación por claves, no por desplazamiento: nada se repite ni se salta
     * aunque se formalicen contrataciones nuevas entre páginas. Es un método aparte y no un parámetro nulo porque
     * PostgreSQL no infiere el tipo de un {@code null} sin tipo.
     */
    @Query("""
            select c from ServiceContract c
            where c.providerId = :providerId
              and (c.contractedAt < :cursorContractedAt
                   or (c.contractedAt = :cursorContractedAt and c.id < :cursorId))
            order by c.contractedAt desc, c.id desc
            """)
    Slice<ServiceContract> findProviderHistoryAfter(@Param("providerId") UUID providerId,
                                                    @Param("cursorContractedAt") Instant cursorContractedAt,
                                                    @Param("cursorId") UUID cursorId, Pageable pageable);

    /** Historial del cliente (GC-448): las contrataciones de sus solicitudes, igual que el del prestador. */
    @Query("""
            select c from ServiceContract c
            where c.clientId = :clientId
            order by c.contractedAt desc, c.id desc
            """)
    Slice<ServiceContract> findClientHistoryFrom(@Param("clientId") UUID clientId, Pageable pageable);

    /** Las contrataciones del cliente estrictamente posteriores en el orden a la clave del cursor. */
    @Query("""
            select c from ServiceContract c
            where c.clientId = :clientId
              and (c.contractedAt < :cursorContractedAt
                   or (c.contractedAt = :cursorContractedAt and c.id < :cursorId))
            order by c.contractedAt desc, c.id desc
            """)
    Slice<ServiceContract> findClientHistoryAfter(@Param("clientId") UUID clientId,
                                                  @Param("cursorContractedAt") Instant cursorContractedAt,
                                                  @Param("cursorId") UUID cursorId, Pageable pageable);
}
