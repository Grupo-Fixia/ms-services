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
}
