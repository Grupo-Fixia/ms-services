package com.fixia.services.service;

import com.fixia.services.domain.ActorRole;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.domain.ServiceRequestTransition;
import com.fixia.services.exception.ContractNotFoundException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contrataciones (GC-359): se formalizan al aceptar una cotización y las consultan el cliente dueño de la solicitud y
 * el prestador contratado. Es el único camino por el que el prestador recibe la dirección exacta (solo existe con la
 * solicitud contratada o en curso, ADR-009). El prestador contratado también inicia el servicio (GC-437). Los actores
 * siempre salen del token, nunca del cuerpo.
 */
@Service
public class ContractService {

    private final ServiceContractRepository contractRepository;
    private final ServiceRequestRepository serviceRequestRepository;
    private final ServiceRequestHistory history;
    private final BusinessEventRecorder events;
    private final Clock clock;

    public ContractService(ServiceContractRepository contractRepository,
                           ServiceRequestRepository serviceRequestRepository, ServiceRequestHistory history,
                           BusinessEventRecorder events, Clock clock) {
        this.contractRepository = contractRepository;
        this.serviceRequestRepository = serviceRequestRepository;
        this.history = history;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Formaliza la contratación al aceptar la cotización. Se ejecuta dentro de la transacción de la aceptación, con la
     * solicitud ya en {@code CONTRACTED} y la cotización en {@code ACCEPTED}: si algo falla, no queda nada a medias.
     */
    @Transactional
    public ServiceContract formalize(ServiceRequest request, Quotation quotation, Instant now) {
        return contractRepository.save(ServiceContract.formalize(request, quotation, now));
    }

    /**
     * El prestador contratado inicia el servicio (GC-437): {@code CONTRACTED -> IN_PROGRESS}. En una sola transacción y
     * con la solicitud bloqueada se cambia el estado, se anota en el historial y se registra {@code servicio.iniciado}
     * en el Outbox; si algo falla, no queda ninguna de las tres cosas.
     *
     * <ol>
     *   <li>Una contratación ajena o inexistente da la misma 404.
     *   <li>Repetir el inicio sobre un servicio que ya está en curso devuelve el estado actual sin cambiar nada ni
     *       publicar otro evento, para que reintentar tras perder la respuesta no parezca un fallo. Dos inicios
     *       simultáneos se aplican uno tras otro por el bloqueo: el segundo ve el servicio en curso.
     *   <li>Desde cualquier otro estado distinto de {@code CONTRACTED} no se puede iniciar (409).
     * </ol>
     */
    @Transactional
    public ContractView start(UUID providerId, UUID contractId) {
        ServiceContract contract = contractRepository.findByIdAndProviderId(contractId, providerId)
                .orElseThrow(ContractNotFoundException::new);
        ServiceRequest request = serviceRequestRepository.findByIdForUpdate(contract.getServiceRequestId())
                .orElseThrow(ContractNotFoundException::new);
        if (request.getStatus() != ServiceRequestStatus.IN_PROGRESS) {
            Instant now = clock.instant();
            request.start(now);
            history.record(request.getId(), ServiceRequestStatus.CONTRACTED, ServiceRequestStatus.IN_PROGRESS,
                    providerId, ActorRole.PROFESSIONAL, null, now);
            events.record(BusinessEvents.serviceStarted(contract, now), request.getId(),
                    BusinessEvents.Actor.professional(providerId), now);
        }
        return new ContractView(contract, request, history.timeline(request.getId()));
    }

    /**
     * La contratación de una solicitud del cliente. Si la solicitud no existe o es de otro cliente la respuesta es la
     * misma 404; si todavía no se contrató, también 404 ("no existe la contratación").
     */
    @Transactional(readOnly = true)
    public ContractView getForClient(UUID clientId, UUID serviceRequestId) {
        ServiceRequest request = serviceRequestRepository.findById(serviceRequestId)
                .filter(found -> found.getClientId().equals(clientId))
                .orElseThrow(ServiceRequestNotFoundException::new);
        ServiceContract contract = contractRepository.findByServiceRequestId(request.getId())
                .orElseThrow(ContractNotFoundException::new);
        return new ContractView(contract, request, history.timeline(request.getId()));
    }

    /** Contrataciones del prestador, la más reciente primero. Sin historial: este se pide al consultar una contratación. */
    @Transactional(readOnly = true)
    public List<ContractView> listMine(UUID providerId) {
        List<ServiceContract> contracts = contractRepository.findByProviderIdOrderByContractedAtDescIdDesc(providerId);
        Map<UUID, ServiceRequest> requests = serviceRequestRepository
                .findAllById(contracts.stream().map(ServiceContract::getServiceRequestId).toList()).stream()
                .collect(Collectors.toMap(ServiceRequest::getId, Function.identity()));
        return contracts.stream()
                .map(contract -> new ContractView(contract, requests.get(contract.getServiceRequestId()), null))
                .toList();
    }

    /** Una contratación del prestador. Si no existe o es de otro prestador, la respuesta es la misma (404). */
    @Transactional(readOnly = true)
    public ContractView getMine(UUID providerId, UUID contractId) {
        ServiceContract contract = contractRepository.findByIdAndProviderId(contractId, providerId)
                .orElseThrow(ContractNotFoundException::new);
        ServiceRequest request = serviceRequestRepository.findById(contract.getServiceRequestId())
                .orElseThrow(ContractNotFoundException::new);
        return new ContractView(contract, request, history.timeline(request.getId()));
    }

    /**
     * La contratación con su solicitud y el historial de la solicitud, cargados dentro de la transacción para armar la
     * respuesta fuera de ella. {@code timeline} es nulo en el listado, que no lo incluye.
     */
    public record ContractView(ServiceContract contract, ServiceRequest request,
                               List<ServiceRequestTransition> timeline) {
    }
}
