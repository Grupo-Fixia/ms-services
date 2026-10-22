package com.fixia.services.repository;

import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuotationRepository extends JpaRepository<Quotation, UUID> {

    /** Historial de cotizaciones de una solicitud, la más reciente primero. */
    List<Quotation> findByServiceRequestIdOrderByCreatedAtDesc(UUID serviceRequestId);

    /** La cotización de la solicitud que está en el estado indicado (por ejemplo, la enviada o la aceptada). */
    Optional<Quotation> findFirstByServiceRequestIdAndStatusOrderByCreatedAtDesc(UUID serviceRequestId,
                                                                                QuotationStatus status);

    /** Todas las cotizaciones de la solicitud en un estado; al enviar una nueva se cierran las {@code SENT} (GC-354). */
    List<Quotation> findByServiceRequestIdAndStatus(UUID serviceRequestId, QuotationStatus status);

    /** Cotizaciones del prestador, la más reciente primero. */
    List<Quotation> findByProviderIdOrderByCreatedAtDesc(UUID providerId);

    /** Cotizaciones del prestador para una solicitud, la más reciente primero. */
    List<Quotation> findByProviderIdAndServiceRequestIdOrderByCreatedAtDesc(UUID providerId, UUID serviceRequestId);

    /** Una cotización del prestador; vacío si no existe o es de otro prestador. */
    Optional<Quotation> findByIdAndProviderId(UUID id, UUID providerId);
}
