package com.fixia.services.repository;

import com.fixia.services.domain.ProviderType;
import com.fixia.services.domain.ServiceRequestApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRequestApplicationRepository extends JpaRepository<ServiceRequestApplication, UUID> {

    /** Postulaciones del técnico, la más reciente primero. */
    List<ServiceRequestApplication> findByProviderIdOrderByCreatedAtDesc(UUID providerId);

    /**
     * Postulaciones que ve el técnico: las suyas y las de su empresa que lo proponen como técnico designado (GC-525),
     * la más reciente primero.
     */
    @Query("""
            select a from ServiceRequestApplication a
            where a.providerId = :technicianId or a.technicianId = :technicianId
            order by a.createdAt desc""")
    List<ServiceRequestApplication> findVisibleToTechnician(@Param("technicianId") UUID technicianId);

    /** Una postulación del técnico; vacío si no existe o es de otro técnico. */
    Optional<ServiceRequestApplication> findByIdAndProviderId(UUID id, UUID providerId);

    /** Una postulación propia o en la que la empresa lo designó; vacío en cualquier otro caso. */
    @Query("""
            select a from ServiceRequestApplication a
            where a.id = :id and (a.providerId = :technicianId or a.technicianId = :technicianId)""")
    Optional<ServiceRequestApplication> findVisibleToTechnician(@Param("id") UUID id,
                                                                @Param("technicianId") UUID technicianId);

    /** La postulación del técnico a la solicitud, si ya existe (GC-343). */
    Optional<ServiceRequestApplication> findByServiceRequestIdAndProviderId(UUID serviceRequestId, UUID providerId);

    /**
     * Postulaciones de una solicitud en orden de llegada (para consultar postulantes y seleccionar, GC-347/348). El id
     * desempata las que comparten instante, para que el orden sea el mismo en cada consulta.
     */
    List<ServiceRequestApplication> findByServiceRequestIdOrderByCreatedAtAscIdAsc(UUID serviceRequestId);

    /** Postulaciones de una empresa con ese técnico como propuesto (GC-524: su trabajo antes de desvincularse). */
    List<ServiceRequestApplication> findByProviderIdAndTechnicianIdAndProviderTypeOrderByCreatedAtAscIdAsc(
            UUID providerId, UUID technicianId, ProviderType providerType);
}
