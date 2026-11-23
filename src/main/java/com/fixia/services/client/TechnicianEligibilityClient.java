package com.fixia.services.client;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.replication.UserReplica;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ¿Puede un técnico atender una solicitud? (RF-011, RF-010, ADR-041). Desde GC-547 ya no se pregunta a ms-users: se
 * decide con la réplica local de su estado ({@link UserReplica}), con las mismas reglas que ms-users evaluaba: categoría
 * entre sus especialidades, zona que cubre la ubicación aproximada, franja que contiene el momento esperado y, si
 * ms-users la exige, verificación vigente. Un técnico que la réplica no tiene o cuya cuenta no está activa no es
 * elegible ({@code TECHNICIAN_NOT_FOUND}).
 *
 * <p>La réplica puede ir unos segundos atrasada: un técnico que acaba de cambiar su zona o sus franjas puede verse con
 * las anteriores durante ese lapso (consistencia eventual aceptada en GC-547).
 */
@Component
public class TechnicianEligibilityClient {

    /** Cabecera de la clave interna, que siguen usando las rutas internas propias de ms-services. */
    static final String API_KEY_HEADER = "X-Internal-Api-Key";

    /**
     * Resultado por solicitud. {@code reasons} son códigos estables como {@code CATEGORY}, {@code ZONE}.
     * {@code companyMember} dice si el técnico pertenece a una empresa (contrato de empresas, D9); no cambia
     * {@code eligible}, porque la misma consulta sirve cuando su empresa lo propone.
     */
    public record Result(UUID requestId, Boolean eligible, List<String> reasons, Boolean companyMember) {

        public Result(UUID requestId, Boolean eligible, List<String> reasons) {
            this(requestId, eligible, reasons, null);
        }

        /** El técnico está en una empresa: no se postula ni ve oportunidades por su cuenta. */
        public boolean isCompanyMember() {
            return Boolean.TRUE.equals(companyMember);
        }
    }

    private final UserReplica replica;

    public TechnicianEligibilityClient(UserReplica replica) {
        this.replica = replica;
    }

    public Result check(UUID technicianUserId, ServiceRequest request) {
        return checkAll(technicianUserId, List.of(request)).get(request.getId());
    }

    /** Un resultado por solicitud, indexado por su id. */
    public Map<UUID, Result> checkAll(UUID technicianUserId, List<ServiceRequest> requests) {
        Optional<UserReplica.Technician> technician = replica.activeTechnician(technicianUserId);
        boolean companyMember = technician.map(found -> found.companyAdminUserId() != null).orElse(false);
        Map<UUID, Result> byRequest = new HashMap<>();
        for (ServiceRequest request : requests) {
            List<String> reasons = technician.map(found -> reasons(found, request))
                    .orElse(List.of("TECHNICIAN_NOT_FOUND"));
            byRequest.put(request.getId(), new Result(request.getId(), reasons.isEmpty(), reasons, companyMember));
        }
        return byRequest;
    }

    private static List<String> reasons(UserReplica.Technician technician, ServiceRequest request) {
        List<String> reasons = new ArrayList<>();
        if (!technician.verificationRequirementMet()) {
            reasons.add("VERIFICATION");
        }
        if (!technician.offers(request.getCategory().name())) {
            reasons.add("CATEGORY");
        }
        ApproximateLocation location = request.getApproximateLocation();
        if (!technician.covers(location.city(), location.latitude(), location.longitude())) {
            reasons.add("ZONE");
        }
        if (!technician.isAvailableAt(request.getExpectedAt())) {
            reasons.add("AVAILABILITY");
        }
        return reasons;
    }
}
