package com.fixia.services.repository;

import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuotationRepository extends JpaRepository<Quotation, UUID> {

    /** Historial de cotizaciones de una solicitud, la versión más reciente primero (GC-427). */
    List<Quotation> findByServiceRequestIdOrderByRevisionDesc(UUID serviceRequestId);

    /**
     * La versión más alta de la solicitud, 0 si no tiene cotizaciones. Se usa para asignar la siguiente al enviar; es segura
     * porque el envío ya tiene bloqueada la fila de la solicitud.
     */
    @Query("select coalesce(max(q.revision), 0) from Quotation q where q.serviceRequestId = :serviceRequestId")
    int maxRevision(@Param("serviceRequestId") UUID serviceRequestId);

    /** La cotización de la solicitud que está en el estado indicado (por ejemplo, la enviada o la aceptada). */
    Optional<Quotation> findFirstByServiceRequestIdAndStatusOrderByCreatedAtDesc(UUID serviceRequestId,
                                                                                QuotationStatus status);

    /** Todas las cotizaciones de la solicitud en un estado; al enviar una nueva se cierran las {@code SENT} (GC-354). */
    List<Quotation> findByServiceRequestIdAndStatus(UUID serviceRequestId, QuotationStatus status);

    /**
     * Cotizaciones del prestador de todas sus solicitudes, la más reciente primero. La versión solo se compara dentro de
     * una solicitud, así que aquí se ordena por fecha y, a igual instante, por id para que el orden sea estable.
     */
    List<Quotation> findByProviderIdOrderByCreatedAtDescIdDesc(UUID providerId);

    /** Cotizaciones del prestador para una solicitud, la versión más reciente primero. */
    List<Quotation> findByProviderIdAndServiceRequestIdOrderByRevisionDesc(UUID providerId, UUID serviceRequestId);

    /** Una cotización del prestador; vacío si no existe o es de otro prestador. */
    Optional<Quotation> findByIdAndProviderId(UUID id, UUID providerId);

    /** Cotizaciones que envió el técnico (GC-551), como independiente o como designado de una empresa. */
    List<Quotation> findByAuthorIdOrderByCreatedAtDescIdDesc(UUID authorId);

    /** Las cotizaciones que envió el técnico en una solicitud, la versión más reciente primero (GC-551). */
    List<Quotation> findByAuthorIdAndServiceRequestIdOrderByRevisionDesc(UUID authorId, UUID serviceRequestId);

    /** Una cotización que envió el técnico; si la envió otro, no se encuentra (GC-551). */
    Optional<Quotation> findByIdAndAuthorId(UUID id, UUID authorId);

    /** Una cotización solo se responde dentro de su solicitud: una de otra solicitud no se encuentra. */
    Optional<Quotation> findByIdAndServiceRequestId(UUID id, UUID serviceRequestId);
}
