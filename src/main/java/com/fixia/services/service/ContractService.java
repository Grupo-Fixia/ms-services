package com.fixia.services.service;

import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.exception.ContractNotFoundException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contrataciones (GC-359): se formalizan al aceptar una cotización y las consultan el cliente dueño de la solicitud y
 * el prestador contratado. Es el único camino por el que el prestador recibe la dirección exacta (solo existe con la
 * solicitud contratada, ADR-009). Los actores siempre salen del token, nunca del cuerpo.
 */
@Service
public class ContractService {

    private final ServiceContractRepository contractRepository;
    private final ServiceRequestRepository serviceRequestRepository;

    public ContractService(ServiceContractRepository contractRepository,
                           ServiceRequestRepository serviceRequestRepository) {
        this.contractRepository = contractRepository;
        this.serviceRequestRepository = serviceRequestRepository;
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
        return new ContractView(contract, request);
    }

    /** Contrataciones del prestador, la más reciente primero. */
    @Transactional(readOnly = true)
    public List<ContractView> listMine(UUID providerId) {
        List<ServiceContract> contracts = contractRepository.findByProviderIdOrderByContractedAtDescIdDesc(providerId);
        Map<UUID, ServiceRequest> requests = serviceRequestRepository
                .findAllById(contracts.stream().map(ServiceContract::getServiceRequestId).toList()).stream()
                .collect(Collectors.toMap(ServiceRequest::getId, Function.identity()));
        return contracts.stream()
                .map(contract -> new ContractView(contract, requests.get(contract.getServiceRequestId())))
                .toList();
    }

    /** Una contratación del prestador. Si no existe o es de otro prestador, la respuesta es la misma (404). */
    @Transactional(readOnly = true)
    public ContractView getMine(UUID providerId, UUID contractId) {
        ServiceContract contract = contractRepository.findByIdAndProviderId(contractId, providerId)
                .orElseThrow(ContractNotFoundException::new);
        ServiceRequest request = serviceRequestRepository.findById(contract.getServiceRequestId())
                .orElseThrow(ContractNotFoundException::new);
        return new ContractView(contract, request);
    }

    /** La contratación con su solicitud, cargadas dentro de la transacción para armar la respuesta fuera de ella. */
    public record ContractView(ServiceContract contract, ServiceRequest request) {
    }
}
