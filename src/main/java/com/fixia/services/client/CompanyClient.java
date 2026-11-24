package com.fixia.services.client;

import com.fixia.services.replication.UserReplica;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Empresas y sus técnicos (GC-468, GC-551). Desde GC-547 se leen de la réplica local del estado de técnicos y empresas
 * que publica ms-users, sin llamarlo, también al aceptar una cotización. La empresa siempre se identifica por la cuenta
 * de su administrador ({@code users.id}, el {@code sub} del token con el que se postuló).
 *
 * <p>Un técnico pertenece a la empresa si su cuenta y la de la empresa están activas y su estado la nombra
 * ({@code companyAdminUserId}), igual que respondía ms-users. La réplica puede ir unos segundos atrasada: si un técnico
 * se desvincula en ese lapso, la compensación de GC-547 ({@code CompanyDepartureCompensation}) vence sus cotizaciones
 * pendientes con la empresa y registra cualquier contratación que haya quedado en conflicto.
 */
@Component
public class CompanyClient {

    /**
     * Nombre comercial de la empresa; nunca su NIT ni los datos de la cuenta. {@code reputation} (GC-551) es el promedio
     * simple de las reputaciones de sus técnicos asociados que ya tienen evaluaciones; {@code null} si ninguno tiene.
     */
    public record CompanyProfile(UUID companyAdminUserId, String name, TechnicianProfileClient.Reputation reputation) {

        public CompanyProfile(UUID companyAdminUserId, String name) {
            this(companyAdminUserId, name, null);
        }
    }

    private final UserReplica replica;

    public CompanyClient(UserReplica replica) {
        this.replica = replica;
    }

    /** Si el técnico pertenece hoy a la empresa de esa cuenta; "no" en cualquier otro caso, sin distinguir el motivo. */
    public boolean isTechnicianAssociated(UUID companyAdminUserId, UUID technicianUserId) {
        return replica.activeCompany(companyAdminUserId).isPresent()
                && replica.activeTechnician(technicianUserId)
                .map(technician -> companyAdminUserId.equals(technician.companyAdminUserId()))
                .orElse(false);
    }

    /** Técnicos activos de la empresa de esa cuenta, ordenados por id; lista vacía si la empresa no está activa. */
    public List<UUID> findTechnicians(UUID companyAdminUserId) {
        if (replica.activeCompany(companyAdminUserId).isEmpty()) {
            return List.of();
        }
        return replica.allActiveTechnicians().stream()
                .filter(technician -> companyAdminUserId.equals(technician.companyAdminUserId()))
                .map(UserReplica.Technician::userId)
                .toList();
    }

    /** @return el perfil de cada empresa activa, indexado por la cuenta de su administrador */
    public Map<UUID, CompanyProfile> findPublicProfiles(Collection<UUID> companyAdminUserIds) {
        Map<UUID, CompanyProfile> profiles = new LinkedHashMap<>();
        replica.activeCompanies(new LinkedHashSet<>(companyAdminUserIds)).forEach((id, company) ->
                profiles.put(id, new CompanyProfile(id, company.name(),
                        TechnicianProfileClient.reputation(company.reputation()))));
        return profiles;
    }
}
