package com.fixia.services.service;

import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.dto.CreateQuotationRequest;
import com.fixia.services.exception.InvalidQuotationException;
import com.fixia.services.exception.InvalidQuotationTransitionException;
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
 * consultan. El cliente las acepta o rechaza (GC-358). Los actores siempre salen del token, nunca del cuerpo.
 */
@Service
public class QuotationService {

    private final ServiceRequestRepository serviceRequestRepository;
    private final QuotationRepository quotationRepository;
    private final ContractService contractService;
    private final Clock clock;

    public QuotationService(ServiceRequestRepository serviceRequestRepository,
                            QuotationRepository quotationRepository, ContractService contractService, Clock clock) {
        this.serviceRequestRepository = serviceRequestRepository;
        this.quotationRepository = quotationRepository;
        this.contractService = contractService;
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

    /**
     * El cliente acepta una cotización de su solicitud (GC-358) y, en la misma transacción, Fixia formaliza la
     * contratación (GC-359, CA-03): la solicitud pasa a {@code CONTRACTED} (desde ahí se entrega la dirección exacta) y
     * queda guardada una copia de lo acordado. Nada queda a medias: si algo falla, la cotización sigue enviada.
     */
    @Transactional
    public Quotation accept(UUID clientId, UUID serviceRequestId, UUID quotationId) {
        return respond(clientId, serviceRequestId, quotationId, QuotationStatus.ACCEPTED);
    }

    /**
     * El cliente rechaza una cotización de su solicitud (GC-358). La solicitud sigue con su prestador seleccionado,
     * que puede enviar otra cotización.
     */
    @Transactional
    public Quotation reject(UUID clientId, UUID serviceRequestId, UUID quotationId) {
        return respond(clientId, serviceRequestId, quotationId, QuotationStatus.REJECTED);
    }

    /**
     * Respuesta del cliente a una cotización, con la solicitud bloqueada hasta el fin de la transacción (la misma
     * fila que toman el envío, la postulación y la selección): dos respuestas simultáneas, o una respuesta y un envío
     * nuevo, no se cruzan.
     *
     * <ol>
     *   <li>La solicitud debe ser del cliente y la cotización de esa solicitud; si no, la respuesta es la misma 404
     *       (no se revela qué existe).
     *   <li>Repetir la misma respuesta (aceptar una ya aceptada, rechazar una ya rechazada) devuelve el estado
     *       actual sin cambiar nada, para que reintentar tras perder la respuesta no parezca un fallo. Si una
     *       cotización aceptada con GC-358 quedó sin contratar (la solicitud sigue con su prestador), repetir la
     *       aceptación completa la contratación.
     *   <li>La cotización debe estar enviada: una rechazada, reemplazada, vencida o ya respondida de otra forma no se
     *       acepta ni se rechaza (409), de modo que ninguna versión anterior o modificada se acepta sin una nueva
     *       cotización (RF-033).
     *   <li>La solicitud debe seguir con prestador seleccionado, y la cotización ser de ese prestador (409).
     *   <li>Aceptar exige que no haya ya otra aceptada (409) y que la cotización esté vigente.
     * </ol>
     */
    private Quotation respond(UUID clientId, UUID serviceRequestId, UUID quotationId, QuotationStatus decision) {
        ServiceRequest request = serviceRequestRepository.findByIdAndClientIdForUpdate(serviceRequestId, clientId)
                .orElseThrow(ServiceRequestNotFoundException::new);
        Quotation quotation = quotationRepository.findByIdAndServiceRequestId(quotationId, request.getId())
                .orElseThrow(QuotationNotFoundException::new);
        Instant now = clock.instant();
        if (quotation.getStatus() == decision) {
            if (decision == QuotationStatus.ACCEPTED && isAwaitingContract(request, quotation)) {
                contract(request, quotation, now);
            }
            return quotation;
        }
        if (quotation.getStatus() != QuotationStatus.SENT) {
            throw new InvalidQuotationTransitionException(quotation.getStatus(), decision);
        }
        if (!isAwaitingContract(request, quotation)) {
            throw new QuotationNotAllowedException("La solicitud ya no admite responder cotizaciones");
        }
        if (decision == QuotationStatus.ACCEPTED) {
            if (quotationRepository.findFirstByServiceRequestIdAndStatusOrderByCreatedAtDesc(request.getId(),
                    QuotationStatus.ACCEPTED).isPresent()) {
                throw new QuotationNotAllowedException("La solicitud ya tiene una cotización aceptada");
            }
            quotation.accept(now);
            contract(request, quotation, now);
        } else {
            quotation.reject(now);
        }
        return quotation;
    }

    /** La solicitud sigue con su prestador seleccionado y la cotización es la de ese prestador. */
    private static boolean isAwaitingContract(ServiceRequest request, Quotation quotation) {
        return request.getStatus() == ServiceRequestStatus.PROVIDER_SELECTED
                && quotation.getProviderId().equals(request.getSelectedProviderId());
    }

    /** Formaliza la contratación: la solicitud pasa a {@code CONTRACTED} y se guarda lo acordado (GC-359). */
    private void contract(ServiceRequest request, Quotation quotation, Instant now) {
        request.contract(now);
        contractService.formalize(request, quotation, now);
    }

    /** El instante con el que se calcula, al responder, si una cotización enviada ya venció. */
    public Instant now() {
        return clock.instant();
    }
}
