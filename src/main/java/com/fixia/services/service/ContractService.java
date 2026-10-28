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
     * El prestador contratado declara que terminó (GC-441): {@code IN_PROGRESS -> TECHNICIAN_FINISHED}. No cierra el
     * servicio (RF-047): el cierre lo confirma el cliente. Las evidencias son opcionales y no se piden aquí. Mismas
     * garantías que {@link #start}: una sola transacción, solicitud bloqueada y 404 igual para una contratación ajena.
     * Repetirlo sobre un servicio ya finalizado devuelve el estado actual sin cambios ni evento nuevo; desde cualquier
     * otro estado distinto de {@code IN_PROGRESS} (sin iniciar, cerrado) es 409.
     */
    @Transactional
    public ContractView finish(UUID providerId, UUID contractId) {
        ServiceContract contract = contractRepository.findByIdAndProviderId(contractId, providerId)
                .orElseThrow(ContractNotFoundException::new);
        ServiceRequest request = serviceRequestRepository.findByIdForUpdate(contract.getServiceRequestId())
                .orElseThrow(ContractNotFoundException::new);
        if (request.getStatus() != ServiceRequestStatus.TECHNICIAN_FINISHED) {
            Instant now = clock.instant();
            request.finish(now);
            history.record(request.getId(), ServiceRequestStatus.IN_PROGRESS, ServiceRequestStatus.TECHNICIAN_FINISHED,
                    providerId, ActorRole.PROFESSIONAL, null, now);
            events.record(BusinessEvents.serviceFinished(contract, now), request.getId(),
                    BusinessEvents.Actor.professional(providerId), now);
        }
        return new ContractView(contract, request, history.timeline(request.getId()));
    }

    /**
     * El cliente dueño confirma el cierre (GC-441): {@code TECHNICIAN_FINISHED -> CLOSED}. Es definitivo: un servicio
     * cerrado no vuelve a ejecución por una petición tardía. Confirmar un servicio ya cerrado devuelve el estado actual
     * sin duplicar nada (cierre doble); desde cualquier otro estado distinto de {@code TECHNICIAN_FINISHED} es 409.
     * Una solicitud ajena o inexistente da la misma 404.
     */
    @Transactional
    public ContractView confirmClosure(UUID clientId, UUID serviceRequestId) {
        ServiceRequest request = serviceRequestRepository.findByIdAndClientIdForUpdate(serviceRequestId, clientId)
                .orElseThrow(ServiceRequestNotFoundException::new);
        if (request.getStatus() != ServiceRequestStatus.CLOSED) {
            Instant now = clock.instant();
            // Valida el estado antes de buscar la contratación: una solicitud sin contratar es un 409, no un 404.
            request.close(now);
            ServiceContract contract = contractOf(request);
            history.record(request.getId(), ServiceRequestStatus.TECHNICIAN_FINISHED, ServiceRequestStatus.CLOSED,
                    clientId, ActorRole.CLIENT, null, now);
            events.record(BusinessEvents.serviceClosed(contract, now), request.getId(),
                    BusinessEvents.Actor.client(clientId), now);
        }
        return new ContractView(contractOf(request), request, history.timeline(request.getId()));
    }

    /**
     * El cliente dueño objeta el cierre (GC-441): {@code TECHNICIAN_FINISHED -> IN_PROGRESS}, con el motivo en el
     * historial. No es un cierre satisfactorio y no hay disputa formal (RF-049, RF-050, fuera del sprint). El motivo
     * puede traer datos personales: se guarda en el historial, que ven los dos participantes, y no viaja en el evento.
     *
     * <p>Solo se puede objetar un servicio {@code TECHNICIAN_FINISHED}; desde {@code CLOSED} u otro estado, 409. Repetir
     * la misma objeción (mismo cliente y mismo motivo) cuando es justo el último cambio del servicio devuelve el estado
     * actual, para que reintentar tras perder la respuesta no parezca un fallo.
     */
    @Transactional
    public ContractView objectClosure(UUID clientId, UUID serviceRequestId, String reason) {
        ServiceRequest request = serviceRequestRepository.findByIdAndClientIdForUpdate(serviceRequestId, clientId)
                .orElseThrow(ServiceRequestNotFoundException::new);
        String cleanReason = reason.strip();
        if (!isRepeatedObjection(request, clientId, cleanReason)) {
            Instant now = clock.instant();
            request.objectClosure(now);
            ServiceContract contract = contractOf(request);
            history.record(request.getId(), ServiceRequestStatus.TECHNICIAN_FINISHED, ServiceRequestStatus.IN_PROGRESS,
                    clientId, ActorRole.CLIENT, cleanReason, now);
            events.record(BusinessEvents.closureObjected(contract, now), request.getId(),
                    BusinessEvents.Actor.client(clientId), now);
        }
        return new ContractView(contractOf(request), request, history.timeline(request.getId()));
    }

    /** El servicio sigue en ejecución y su último cambio es justamente esta objeción del mismo cliente. */
    private boolean isRepeatedObjection(ServiceRequest request, UUID clientId, String reason) {
        return request.getStatus() == ServiceRequestStatus.IN_PROGRESS
                && history.latest(request.getId())
                .filter(last -> last.getFromStatus() == ServiceRequestStatus.TECHNICIAN_FINISHED)
                .filter(last -> last.getToStatus() == ServiceRequestStatus.IN_PROGRESS)
                .filter(last -> clientId.equals(last.getActorId()))
                .filter(last -> reason.equals(last.getReason()))
                .isPresent();
    }

    private ServiceContract contractOf(ServiceRequest request) {
        return contractRepository.findByServiceRequestId(request.getId()).orElseThrow(ContractNotFoundException::new);
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
