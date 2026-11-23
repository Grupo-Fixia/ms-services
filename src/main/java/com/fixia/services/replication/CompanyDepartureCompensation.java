package com.fixia.services.replication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Compensación por la consistencia eventual de la pertenencia a empresas (GC-547). Sin llamadas síncronas, ms-users
 * decide la desvinculación con una copia del trabajo del técnico y ms-services decide las contrataciones con una copia de
 * la pertenencia; ambas pueden ir unos segundos atrasadas. Cuando llega el estado de un técnico que ya no pertenece a la
 * empresa que tenía:
 *
 * <ul>
 *   <li>sus cotizaciones enviadas en nombre de esa empresa pasan a {@code EXPIRED}, para que el cliente no pueda aceptar
 *       una oferta sin ejecutor;</li>
 *   <li>si quedó una contratación activa con él como ejecutor de esa empresa (la carrera de unos segundos), se registra
 *       como conflicto para el administrador, que puede reembolsar o reasignar.</li>
 * </ul>
 */
@Component
public class CompanyDepartureCompensation implements ReplicatedStateObserver {

    private static final Logger log = LoggerFactory.getLogger(CompanyDepartureCompensation.class);

    private static final Set<ServiceRequestStatus> ACTIVE = EnumSet.of(ServiceRequestStatus.CONTRACTED,
            ServiceRequestStatus.IN_PROGRESS, ServiceRequestStatus.TECHNICIAN_FINISHED);

    private final QuotationRepository quotations;
    private final ServiceContractRepository contracts;
    private final ServiceRequestRepository requests;
    private final Clock clock;

    public CompanyDepartureCompensation(QuotationRepository quotations, ServiceContractRepository contracts,
                                        ServiceRequestRepository requests, Clock clock) {
        this.quotations = quotations;
        this.contracts = contracts;
        this.requests = requests;
        this.clock = clock;
    }

    @Override
    public void onChange(StateEvent event, JsonNode previousData, boolean previouslyRemoved) {
        if (!UserReplica.TECHNICIAN_STATE.equals(event.stateType()) || previousData == null || previouslyRemoved) {
            return;
        }
        UUID previousCompany = uuid(previousData.path("companyAdminUserId"));
        if (previousCompany == null || !previousData.path("active").asBoolean(false)) {
            return;
        }
        boolean stillMember = !event.removed() && event.data().path("active").asBoolean(false)
                && previousCompany.equals(uuid(event.data().path("companyAdminUserId")));
        if (!stillMember) {
            left(event.key(), previousCompany);
        }
    }

    private void left(UUID technician, UUID company) {
        Instant now = clock.instant();
        for (Quotation quotation : quotations.findByAuthorIdOrderByCreatedAtDescIdDesc(technician)) {
            if (company.equals(quotation.getProviderId()) && quotation.getStatus() == QuotationStatus.SENT) {
                quotation.expireBecauseAuthorLeft(now);
            }
        }
        for (ServiceContract contract : contracts.findByExecutorIdOrderByContractedAtDescIdDesc(technician)) {
            if (company.equals(contract.getProviderId())
                    && requests.findById(contract.getServiceRequestId())
                    .map(request -> ACTIVE.contains(request.getStatus())).orElse(false)) {
                log.warn("Conflicto GC-547: la contratación {} sigue activa con un técnico que ya no pertenece a la "
                        + "empresa; la resuelve el administrador", contract.getId());
            }
        }
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
