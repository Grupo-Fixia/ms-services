package com.fixia.services.service;

import com.fixia.services.domain.ActorRole;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestTransition;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Historial propio de servicios (GC-448): los servicios contratados en los que participó el cliente o el prestador
 * autenticado, del más reciente al más antiguo, con paginación por cursor. Solo lee ms-services: las evaluaciones viven
 * en ms-users y el frontend las pide allí, así que ninguna base lee a la otra. Quien consulta y su rol salen siempre del
 * token, nunca de los parámetros: nadie ve los servicios de otro.
 */
@Service
public class HistoryService {

    private final ServiceContractRepository contractRepository;
    private final ServiceRequestRepository serviceRequestRepository;
    private final ServiceRequestTransitionRepository transitionRepository;

    public HistoryService(ServiceContractRepository contractRepository,
                          ServiceRequestRepository serviceRequestRepository,
                          ServiceRequestTransitionRepository transitionRepository) {
        this.contractRepository = contractRepository;
        this.serviceRequestRepository = serviceRequestRepository;
        this.transitionRepository = transitionRepository;
    }

    /**
     * Una página del historial de {@code userId}, que participa como {@code role} ({@code CLIENT} o
     * {@code PROFESSIONAL}). La solicitud y el historial de transiciones de toda la página se cargan de una vez, no
     * uno por elemento.
     *
     * @param cursor posición de la que sigue la página; nulo para la primera
     */
    @Transactional(readOnly = true)
    public Page page(UUID userId, ActorRole role, int limit, HistoryCursor cursor) {
        return page(userId, role, false, limit, cursor);
    }

    /**
     * Igual que {@link #page(UUID, ActorRole, int, HistoryCursor)}. Con {@code company} (GC-551) el prestador es una
     * empresa y ve los servicios en que fue la prestadora; sin él, un técnico ve los que ejecutó, propios o de su
     * empresa.
     */
    @Transactional(readOnly = true)
    public Page page(UUID userId, ActorRole role, boolean company, int limit, HistoryCursor cursor) {
        Slice<ServiceContract> contracts = contractsOf(userId, role, company, limit, cursor);
        List<UUID> requestIds = contracts.stream().map(ServiceContract::getServiceRequestId).toList();
        Map<UUID, ServiceRequest> requests = serviceRequestRepository.findAllById(requestIds).stream()
                .collect(Collectors.toMap(ServiceRequest::getId, Function.identity()));
        Map<UUID, List<ServiceRequestTransition>> timelines = requestIds.isEmpty() ? Map.of()
                : transitionRepository.findByServiceRequestIdInOrderByOccurredAtAscIdAsc(requestIds).stream()
                .collect(Collectors.groupingBy(ServiceRequestTransition::getServiceRequestId));
        List<Item> items = contracts.stream()
                .map(contract -> new Item(contract, requests.get(contract.getServiceRequestId()), role,
                        timelines.getOrDefault(contract.getServiceRequestId(), List.of())))
                .toList();
        HistoryCursor next = contracts.hasNext() ? HistoryCursor.of(contracts.getContent().getLast()) : null;
        return new Page(items, next);
    }

    private Slice<ServiceContract> contractsOf(UUID userId, ActorRole role, boolean company, int limit,
                                               HistoryCursor cursor) {
        PageRequest size = PageRequest.of(0, limit);
        if (role == ActorRole.CLIENT) {
            return cursor == null
                    ? contractRepository.findClientHistoryFrom(userId, size)
                    : contractRepository.findClientHistoryAfter(userId, cursor.contractedAt(), cursor.id(), size);
        }
        if (company) {
            return cursor == null
                    ? contractRepository.findProviderHistoryFrom(userId, size)
                    : contractRepository.findProviderHistoryAfter(userId, cursor.contractedAt(), cursor.id(), size);
        }
        return cursor == null
                ? contractRepository.findExecutorHistoryFrom(userId, size)
                : contractRepository.findExecutorHistoryAfter(userId, cursor.contractedAt(), cursor.id(), size);
    }

    /** Un servicio de la página con su solicitud y su historial, cargados dentro de la transacción. */
    public record Item(ServiceContract contract, ServiceRequest request, ActorRole role,
                       List<ServiceRequestTransition> timeline) {
    }

    /** Una página; {@code next} es nulo cuando no quedan más servicios. */
    public record Page(List<Item> items, HistoryCursor next) {
    }
}
