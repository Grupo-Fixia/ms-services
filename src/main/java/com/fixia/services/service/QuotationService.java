package com.fixia.services.service;

import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.dto.CreateQuotationRequest;
import com.fixia.services.exception.InvalidQuotationException;
import com.fixia.services.exception.QuotationNotAllowedException;
import com.fixia.services.exception.QuotationNotFoundException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cotizaciones (GC-354): el prestador seleccionado las crea y envía; él y el cliente dueño de la solicitud las
 * consultan. Aceptar o rechazar es GC-358. Los actores siempre salen del token, nunca del cuerpo.
 */
@Service
public class QuotationService {

    private final ServiceRequestRepository serviceRequestRepository;
    private final QuotationRepository quotationRepository;
    private final Clock clock;

    public QuotationService(ServiceRequestRepository serviceRequestRepository,
                            QuotationRepository quotationRepository, Clock clock) {
        this.serviceRequestRepository = serviceRequestRepository;
        this.quotationRepository = quotationRepository;
        this.clock = clock;
    }

    /**
     * Crea y envía una cotización del prestador para la solicitud indicada.
     *
     * <ol>
     *   <li>La solicitud debe existir (404). Se bloquea hasta el fin de la transacción, como en la postulación
     *       (GC-342) y la selección (GC-348), para que dos envíos simultáneos o un envío y una aceptación (GC-358)
     *       no se crucen.
     *   <li>Debe haber una selección vigente para este prestador: la solicitud en {@code PROVIDER_SELECTED} y él como
     *       prestador seleccionado (409). El mensaje es el mismo en ambos casos, para no revelar a quién se eligió.
     *   <li>La solicitud no puede tener ya una cotización aceptada (409).
     *   <li>El dominio valida valor, textos y fechas (400).
     *   <li>La cotización {@code SENT} anterior, si la hay, queda {@code SUPERSEDED} (o {@code EXPIRED} si su vigencia
     *       ya pasó): una solicitud tiene a lo sumo una cotización esperando respuesta y ninguna versión anterior
     *       puede aceptarse (RF-033).
     * </ol>
     */
    @Transactional
    public Quotation send(UUID providerId, CreateQuotationRequest body) {
        ServiceRequest request = serviceRequestRepository.findByIdForUpdate(body.serviceRequestId())
                .orElseThrow(ServiceRequestNotFoundException::new);
        if (request.getStatus() != ServiceRequestStatus.PROVIDER_SELECTED
                || !providerId.equals(request.getSelectedProviderId())) {
            throw new QuotationNotAllowedException("No tienes una selección vigente en esta solicitud");
        }
        if (quotationRepository.findFirstByServiceRequestIdAndStatusOrderByCreatedAtDesc(request.getId(),
                QuotationStatus.ACCEPTED).isPresent()) {
            throw new QuotationNotAllowedException("La solicitud ya tiene una cotización aceptada");
        }

        Instant now = clock.instant();
        Quotation quotation;
        try {
            quotation = Quotation.send(request, providerId, body.amount(), body.currency(), body.scope(),
                    body.conditions(), body.agreedAt(), body.validUntil(), now);
        } catch (IllegalArgumentException e) {
            // Reglas del dominio que la validación del cuerpo no cubre (fechas frente al reloj del servicio).
            throw new InvalidQuotationException(e.getMessage(), e);
        }

        for (Quotation previous : quotationRepository.findByServiceRequestIdAndStatus(request.getId(),
                QuotationStatus.SENT)) {
            if (previous.isExpiredAt(now)) {
                previous.expire(now);
            } else {
                previous.supersede(now);
            }
        }
        return quotationRepository.save(quotation);
    }

    /** Cotizaciones del prestador, opcionalmente de una sola solicitud, la más reciente primero. */
    @Transactional(readOnly = true)
    public List<Quotation> listMine(UUID providerId, UUID serviceRequestId) {
        return serviceRequestId == null
                ? quotationRepository.findByProviderIdOrderByCreatedAtDesc(providerId)
                : quotationRepository.findByProviderIdAndServiceRequestIdOrderByCreatedAtDesc(providerId,
                serviceRequestId);
    }

    /** Una cotización del prestador. Si no existe o es de otro prestador, la respuesta es la misma (404). */
    @Transactional(readOnly = true)
    public Quotation getMine(UUID providerId, UUID quotationId) {
        return quotationRepository.findByIdAndProviderId(quotationId, providerId)
                .orElseThrow(QuotationNotFoundException::new);
    }

    /**
     * Cotizaciones recibidas en una solicitud del cliente, la más reciente primero. Si la solicitud no existe o es de
     * otro cliente, la respuesta es la misma (404).
     */
    @Transactional(readOnly = true)
    public List<Quotation> listForClient(UUID clientId, UUID serviceRequestId) {
        serviceRequestRepository.findById(serviceRequestId)
                .filter(request -> request.getClientId().equals(clientId))
                .orElseThrow(ServiceRequestNotFoundException::new);
        return quotationRepository.findByServiceRequestIdOrderByCreatedAtDesc(serviceRequestId);
    }

    /** El instante con el que se calcula, al responder, si una cotización enviada ya venció. */
    public Instant now() {
        return clock.instant();
    }
}
