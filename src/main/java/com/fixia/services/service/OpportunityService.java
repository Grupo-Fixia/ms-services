package com.fixia.services.service;

import com.fixia.services.client.CompanyClient;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Solicitudes compatibles con un técnico (GC-337, GC-338), las que puede atender y a las que aún puede postularse.
 *
 * <p>Los datos del técnico (especialidades, zona y disponibilidad) viven en ms-users, que es quien evalúa las reglas;
 * este servicio conserva el estado de las solicitudes. Por eso la compatibilidad se resuelve en dos pasos: primero se
 * preseleccionan las solicitudes abiertas en la base propia y luego se pregunta a ms-users por lotes
 * ({@link TechnicianEligibilityClient#checkAll}), de la más reciente a la más antigua.
 *
 * <p><b>Paginación por cursor.</b> Una consulta revisa como máximo {@link #MAX_SCAN} candidatas. Si con eso no reúne
 * {@code limit} oportunidades, devuelve las que encontró y un cursor con la posición alcanzada, desde el que se
 * continúa en la consulta siguiente. Así el tope acota el trabajo de cada consulta pero no oculta nada: recorriendo las
 * páginas hasta que el cursor sea {@code null} se obtienen todas las compatibles. La consulta a ms-users va sin
 * transacción de base abierta y falla cerrada: si no responde, no se devuelve nada ({@code EligibilityCheckException}).
 *
 * <p><b>Empresas (GC-468).</b> Una empresa no tiene especialidades ni zona propias: atiende con sus técnicos. Sus
 * candidatas son las solicitudes abiertas a las que la empresa aún no se ha postulado, y una se le ofrece si al menos
 * uno de sus técnicos activos es elegible para ella; con la oportunidad van esos técnicos, entre los que la empresa
 * escoge al postularse. Misma paginación por cursor, con una consulta de elegibilidad por técnico y lote.
 */
@Service
public class OpportunityService {

    /** Máximo de solicitudes que ms-users acepta en una consulta de elegibilidad. */
    static final int BATCH_SIZE = 100;

    /** Candidatas que se revisan como máximo en una consulta (múltiplo de {@link #BATCH_SIZE}). */
    static final int MAX_SCAN = 500;

    /** Una página de oportunidades y, si quedan candidatas por revisar, el cursor para pedir la siguiente. */
    public record Page(List<ServiceRequest> items, OpportunityCursor next) {
    }

    /** Una solicitud que la empresa puede atender y los técnicos suyos que son elegibles para ella (GC-468). */
    public record CompanyOpportunity(ServiceRequest request, List<UUID> eligibleTechnicianIds) {
    }

    /** Una página de oportunidades de una empresa (GC-468). */
    public record CompanyPage(List<CompanyOpportunity> items, OpportunityCursor next) {
    }

    private record Scan<T>(List<T> items, OpportunityCursor next) {
    }

    private final ServiceRequestRepository repository;
    private final TechnicianEligibilityClient eligibilityClient;
    private final CompanyClient companyClient;
    private final Clock clock;

    public OpportunityService(ServiceRequestRepository repository, TechnicianEligibilityClient eligibilityClient,
                              CompanyClient companyClient, Clock clock) {
        this.repository = repository;
        this.eligibilityClient = eligibilityClient;
        this.companyClient = companyClient;
        this.clock = clock;
    }

    /**
     * @param limit  oportunidades que se piden como máximo en esta página
     * @param cursor posición de la que continuar, o {@code null} para empezar por las más recientes
     */
    public Page findPage(UUID providerId, int limit, OpportunityCursor cursor) {
        Scan<ServiceRequest> scan = scan(providerId, limit, cursor, batch -> {
            Map<UUID, TechnicianEligibilityClient.Result> results = eligibilityClient.checkAll(providerId, batch);
            Map<UUID, ServiceRequest> offered = new LinkedHashMap<>();
            for (ServiceRequest candidate : batch) {
                if (isEligible(results.get(candidate.getId()))) {
                    offered.put(candidate.getId(), candidate);
                }
            }
            return offered;
        });
        return new Page(scan.items(), scan.next());
    }

    /**
     * Oportunidades de la empresa que administra esta cuenta (GC-468). Sin técnicos activos no puede atender nada:
     * página vacía y sin cursor, sin revisar candidatas.
     *
     * @throws com.fixia.services.client.EligibilityCheckException si ms-users no responde (falla cerrada)
     */
    public CompanyPage findCompanyPage(UUID companyAdminUserId, int limit, OpportunityCursor cursor) {
        List<UUID> technicians = companyClient.findTechnicians(companyAdminUserId);
        if (technicians.isEmpty()) {
            return new CompanyPage(List.of(), null);
        }
        Scan<CompanyOpportunity> scan = scan(companyAdminUserId, limit, cursor, batch -> {
            Map<UUID, List<UUID>> eligibleByRequest = new LinkedHashMap<>();
            for (UUID technician : technicians) {
                Map<UUID, TechnicianEligibilityClient.Result> results = eligibilityClient.checkAll(technician, batch);
                for (ServiceRequest candidate : batch) {
                    if (isEligible(results.get(candidate.getId()))) {
                        eligibleByRequest.computeIfAbsent(candidate.getId(), id -> new ArrayList<>()).add(technician);
                    }
                }
            }
            Map<UUID, CompanyOpportunity> offered = new LinkedHashMap<>();
            eligibleByRequest.forEach((id, eligible) -> offered.put(id,
                    new CompanyOpportunity(findIn(batch, id), List.copyOf(eligible))));
            return offered;
        });
        return new CompanyPage(scan.items(), scan.next());
    }

    /**
     * Recorre las candidatas de quien consulta (técnico o empresa: las solicitudes abiertas a las que aún no se ha
     * postulado), de la más reciente a la más antigua, y se queda con las que {@code offered} ofrece de cada lote.
     */
    private <T> Scan<T> scan(UUID applicantId, int limit, OpportunityCursor cursor,
                             Function<List<ServiceRequest>, Map<UUID, T>> offered) {
        // Un solo instante dentro de cada consulta. Entre páginas no se conserva: una solicitud cuyo momento esperado
        // pasa entre dos páginas deja de ofrecerse, como corresponde.
        Instant now = clock.instant();
        List<T> opportunities = new ArrayList<>();
        OpportunityCursor position = cursor;
        int examined = 0;
        Pageable batchSize = PageRequest.of(0, BATCH_SIZE);
        while (examined < MAX_SCAN) {
            Slice<ServiceRequest> candidates = position == null
                    ? repository.findOpenWithoutApplicationFrom(applicantId, now, batchSize)
                    : repository.findOpenWithoutApplicationAfter(applicantId, now, position.publishedAt(),
                    position.id(), batchSize);
            if (!candidates.hasContent()) {
                return new Scan<>(opportunities, null);
            }
            List<ServiceRequest> batch = candidates.getContent();
            Map<UUID, T> results = offered.apply(batch);
            for (int i = 0; i < batch.size(); i++) {
                ServiceRequest candidate = batch.get(i);
                position = OpportunityCursor.of(candidate);
                examined++;
                T opportunity = results.get(candidate.getId());
                if (opportunity != null) {
                    opportunities.add(opportunity);
                    if (opportunities.size() == limit) {
                        boolean noneLeft = i == batch.size() - 1 && !candidates.hasNext();
                        return new Scan<>(opportunities, noneLeft ? null : position);
                    }
                }
            }
            if (!candidates.hasNext()) {
                return new Scan<>(opportunities, null);
            }
        }
        // Presupuesto de revisión agotado sin llegar al límite: quedan candidatas por revisar.
        return new Scan<>(opportunities, position);
    }

    private static ServiceRequest findIn(List<ServiceRequest> batch, UUID id) {
        return batch.stream().filter(candidate -> candidate.getId().equals(id)).findFirst().orElseThrow();
    }

    /** Un resultado ausente se trata como no elegible: nunca se ofrece una solicitud que ms-users no confirmó. */
    private static boolean isEligible(TechnicianEligibilityClient.Result result) {
        return result != null && Boolean.TRUE.equals(result.eligible());
    }
}
