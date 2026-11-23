package com.fixia.services.client;

import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.replication.UserReplica;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Perfil público de los técnicos que se postularon (GC-425). Desde GC-547 se lee de la réplica local del estado que
 * publica ms-users, sin llamarlo. Solo lo que el cliente necesita: nombre corto, verificación, especialidades,
 * descripción, experiencia, ciudad y reputación; nunca el círculo de la zona.
 */
@Component
public class TechnicianProfileClient {

    /**
     * Perfil público del técnico. Los campos que el técnico no ha llenado llegan en {@code null}; la reputación también,
     * si todavía nadie lo ha evaluado.
     */
    public record PublicProfile(UUID userId, String displayName, String verificationStatus,
                                List<ServiceCategory> categories, String professionalDescription,
                                Integer yearsOfExperience, String city, Reputation reputation) {
    }

    /**
     * Reputación que calcula ms-users (GC-446): promedio de 1 a 5 con un decimal y número de evaluaciones de clientes.
     * ms-services no la calcula: la reenvía.
     */
    public record Reputation(BigDecimal average, long count) {
    }

    private final UserReplica replica;

    public TechnicianProfileClient(UserReplica replica) {
        this.replica = replica;
    }

    /** @return el perfil de cada técnico activo, indexado por su id; los que la réplica no tiene simplemente no están */
    public Map<UUID, PublicProfile> findPublicProfiles(Collection<UUID> userIds) {
        Map<UUID, PublicProfile> profiles = new LinkedHashMap<>();
        replica.activeTechnicians(new LinkedHashSet<>(userIds)).forEach((id, technician) ->
                profiles.put(id, profile(technician)));
        return profiles;
    }

    private static PublicProfile profile(UserReplica.Technician technician) {
        List<ServiceCategory> categories = technician.categories() == null ? List.of()
                : technician.categories().stream().map(ServiceCategory::valueOf).toList();
        return new PublicProfile(technician.userId(), technician.displayName(), technician.verificationStatus(),
                categories, technician.professionalDescription(), technician.yearsOfExperience(), technician.city(),
                reputation(technician.reputation()));
    }

    static Reputation reputation(UserReplica.Reputation reputation) {
        return reputation == null ? null : new Reputation(reputation.average(), reputation.count());
    }
}
