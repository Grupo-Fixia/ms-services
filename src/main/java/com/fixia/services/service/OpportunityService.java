package com.fixia.services.service;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private final ServiceRequestRepository repository;
    private final TechnicianEligibilityClient eligibilityClient;
    private final Clock clock;

    public OpportunityService(ServiceRequestRepository repository, TechnicianEligibilityClient eligibilityClient,
                              Clock clock) {
        this.repository = repository;
        this.eligibilityClient = eligibilityClient;
        this.clock = clock;
    }

    /**
     * @param limit  oportunidades que se piden como máximo en esta página
     * @param cursor posición de la que continuar, o {@code null} para empezar por las más recientes
     */
    public Page findPage(UUID providerId, int limit, OpportunityCursor cursor) {
        // Un solo instante dentro de cada consulta. Entre páginas no se conserva: una solicitud cuyo momento esperado
        // pasa entre dos páginas deja de ofrecerse, como corresponde.
        Instant now = clock.instant();
        List<ServiceRequest> opportunities = new ArrayList<>();
        OpportunityCursor position = cursor;
        int examined = 0;
        Pageable batchSize = PageRequest.of(0, BATCH_SIZE);
        while (examined < MAX_SCAN) {
            Slice<ServiceRequest> candidates = position == null
                    ? repository.findOpenWithoutApplicationFrom(providerId, now, batchSize)
                    : repository.findOpenWithoutApplicationAfter(providerId, now, position.publishedAt(),
                    position.id(), batchSize);
            if (!candidates.hasContent()) {
                return new Page(opportunities, null);
            }
            List<ServiceRequest> batch = candidates.getContent();
            Map<UUID, TechnicianEligibilityClient.Result> results = eligibilityClient.checkAll(providerId, batch);
            for (int i = 0; i < batch.size(); i++) {
                ServiceRequest candidate = batch.get(i);
                position = OpportunityCursor.of(candidate);
                examined++;
                if (isEligible(results.get(candidate.getId()))) {
                    opportunities.add(candidate);
                    if (opportunities.size() == limit) {
                        boolean noneLeft = i == batch.size() - 1 && !candidates.hasNext();
                        return new Page(opportunities, noneLeft ? null : position);
                    }
                }
            }
            if (!candidates.hasNext()) {
                return new Page(opportunities, null);
            }
        }
        // Presupuesto de revisión agotado sin llegar al límite: quedan candidatas por revisar.
        return new Page(opportunities, position);
    }

    /** Un resultado ausente se trata como no elegible: nunca se ofrece una solicitud que ms-users no confirmó. */
    private static boolean isEligible(TechnicianEligibilityClient.Result result) {
        return result != null && Boolean.TRUE.equals(result.eligible());
    }
}
