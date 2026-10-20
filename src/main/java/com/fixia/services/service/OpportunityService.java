package com.fixia.services.service;

import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Solicitudes compatibles con un técnico (GC-337), las que puede atender y a las que aún puede postularse.
 *
 * <p>Los datos del técnico (especialidades, zona y disponibilidad) viven en ms-users, que es quien evalúa las reglas;
 * este servicio conserva el estado de las solicitudes. Por eso la compatibilidad se resuelve en dos pasos: primero se
 * preseleccionan las solicitudes abiertas en la base propia ({@link ServiceRequestRepository#findOpenWithoutApplicationFrom})
 * y luego se pregunta a ms-users por lotes ({@link TechnicianEligibilityClient#checkAll}), de la más reciente a la más
 * antigua.
 *
 * <p>La revisión está acotada: se detiene al reunir {@link #LIMIT} oportunidades o al revisar {@link #MAX_SCAN}
 * candidatas, para no recorrer todas las solicitudes publicadas en cada consulta. Aun así, lo que se devuelve es siempre
 * compatible; la paginación completa llega con la API de oportunidades (GC-338). La consulta a ms-users va sin
 * transacción de base abierta, para no retener la conexión mientras se espera su respuesta, y falla cerrada: si no
 * responde, no se devuelve nada ({@code EligibilityCheckException}).
 */
@Service
public class OpportunityService {

    /** Máximo de solicitudes que ms-users acepta en una consulta de elegibilidad. */
    static final int BATCH_SIZE = 100;

    /** Oportunidades que se devuelven como máximo. */
    static final int LIMIT = 50;

    /** Candidatas que se revisan como máximo (múltiplo de {@link #BATCH_SIZE}). */
    static final int MAX_SCAN = 500;

    private final ServiceRequestRepository repository;
    private final TechnicianEligibilityClient eligibilityClient;
    private final Clock clock;

    public OpportunityService(ServiceRequestRepository repository, TechnicianEligibilityClient eligibilityClient,
                              Clock clock) {
        this.repository = repository;
        this.eligibilityClient = eligibilityClient;
        this.clock = clock;
    }

    /** Oportunidades del técnico, la más reciente primero. */
    public List<ServiceRequest> findFor(UUID providerId) {
        // Un solo instante para todo el recorrido, para que las páginas sean coherentes entre sí.
        Instant now = clock.instant();
        List<ServiceRequest> opportunities = new ArrayList<>();
        int scanned = 0;
        Pageable page = PageRequest.of(0, BATCH_SIZE);
        while (scanned < MAX_SCAN && opportunities.size() < LIMIT) {
            Slice<ServiceRequest> candidates = repository.findOpenWithoutApplicationFrom(providerId, now, page);
            if (!candidates.hasContent()) {
                break;
            }
            Map<UUID, TechnicianEligibilityClient.Result> results =
                    eligibilityClient.checkAll(providerId, candidates.getContent());
            for (ServiceRequest candidate : candidates) {
                if (isEligible(results.get(candidate.getId()))) {
                    opportunities.add(candidate);
                    if (opportunities.size() == LIMIT) {
                        return opportunities;
                    }
                }
            }
            scanned += candidates.getNumberOfElements();
            if (!candidates.hasNext()) {
                break;
            }
            page = candidates.nextPageable();
        }
        return opportunities;
    }

    /** Un resultado ausente se trata como no elegible: nunca se ofrece una solicitud que ms-users no confirmó. */
    private static boolean isEligible(TechnicianEligibilityClient.Result result) {
        return result != null && Boolean.TRUE.equals(result.eligible());
    }
}
