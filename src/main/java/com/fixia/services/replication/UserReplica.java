package com.fixia.services.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Copia local de técnicos y empresas que publica ms-users (GC-547), con las reglas que antes evaluaba ms-users en sus
 * rutas internas. Las reglas están portadas de {@code TechnicianEligibilityService}, {@code CoverageZone} y
 * {@code AvailabilitySlot} de ms-users; la de verificación (RN-001) llega ya resuelta en
 * {@code verificationRequirementMet}. Si una cambia en ms-users, hay que cambiarla aquí (queda anotado en el ADR).
 */
@Component
public class UserReplica {

    public static final String TECHNICIAN_STATE = "tecnico.estado";
    public static final String COMPANY_STATE = "empresa.estado";
    public static final String TECHNICIANS_TOPIC = "fixia.users.technician-states";
    public static final String COMPANIES_TOPIC = "fixia.users.company-states";

    /** Las franjas de los técnicos se leen en hora de Colombia (DEC del 2026-10-16). */
    static final ZoneId ZONE = ZoneId.of("America/Bogota");
    private static final double EARTH_RADIUS_KM = 6371.0;

    public record Reputation(BigDecimal average, long count) {
    }

    public record Coverage(String city, Double latitude, Double longitude, Integer radiusKm) {
    }

    public record Slot(String dayOfWeek, String startTime, String endTime) {
    }

    public record Technician(UUID userId, boolean active, String displayName, String verificationStatus,
                             boolean verificationRequirementMet, List<String> categories,
                             String professionalDescription, Integer yearsOfExperience, Coverage coverage,
                             List<Slot> availability, UUID companyAdminUserId, Reputation reputation) {

        public boolean offers(String category) {
            return categories != null && categories.contains(category);
        }

        /** Igual que {@code CoverageZone.covers} de ms-users: misma ciudad y, si hay círculo y punto, dentro de él. */
        public boolean covers(String city, Double latitude, Double longitude) {
            if (coverage == null || !sameCity(coverage.city(), city)) {
                return false;
            }
            if (coverage.radiusKm() == null || coverage.latitude() == null || coverage.longitude() == null
                    || latitude == null || longitude == null) {
                return true;
            }
            return distanceKm(coverage.latitude(), coverage.longitude(), latitude, longitude) <= coverage.radiusKm();
        }

        /** Igual que {@code AvailabilitySlot.includes}: el día y la hora en Colombia caen en alguna franja. */
        public boolean isAvailableAt(Instant instant) {
            if (availability == null || instant == null) {
                return false;
            }
            ZonedDateTime local = instant.atZone(ZONE);
            LocalTime time = local.toLocalTime();
            for (Slot slot : availability) {
                if (local.getDayOfWeek() == DayOfWeek.valueOf(slot.dayOfWeek())
                        && !time.isBefore(LocalTime.parse(slot.startTime()))
                        && time.isBefore(LocalTime.parse(slot.endTime()))) {
                    return true;
                }
            }
            return false;
        }

        public String city() {
            return coverage == null ? null : coverage.city();
        }
    }

    public record Company(UUID companyAdminUserId, boolean active, String name, Reputation reputation) {
    }

    private final ReplicatedStates states;
    private final ObjectMapper objectMapper;

    public UserReplica(ReplicatedStates states, ObjectMapper objectMapper) {
        this.states = states;
        this.objectMapper = objectMapper;
    }

    /** El técnico, si la réplica lo tiene y su cuenta está activa. */
    public Optional<Technician> activeTechnician(UUID userId) {
        return states.find(TECHNICIAN_STATE, userId).map(node -> read(node, Technician.class))
                .filter(Technician::active);
    }

    public Map<UUID, Technician> activeTechnicians(Collection<UUID> userIds) {
        Map<UUID, Technician> found = new LinkedHashMap<>();
        states.findAll(TECHNICIAN_STATE, userIds).forEach((id, node) -> {
            Technician technician = read(node, Technician.class);
            if (technician.active()) {
                found.put(id, technician);
            }
        });
        return found;
    }

    /** Todos los técnicos activos, por id, para búsquedas. */
    public List<Technician> allActiveTechnicians() {
        List<Technician> all = new ArrayList<>();
        for (JsonNode node : states.all(TECHNICIAN_STATE)) {
            Technician technician = read(node, Technician.class);
            if (technician.active()) {
                all.add(technician);
            }
        }
        all.sort(Comparator.comparing(Technician::userId));
        return all;
    }

    public Optional<Company> activeCompany(UUID companyAdminUserId) {
        return states.find(COMPANY_STATE, companyAdminUserId).map(node -> read(node, Company.class))
                .filter(Company::active);
    }

    public Map<UUID, Company> activeCompanies(Collection<UUID> companyAdminUserIds) {
        Map<UUID, Company> found = new LinkedHashMap<>();
        states.findAll(COMPANY_STATE, companyAdminUserIds).forEach((id, node) -> {
            Company company = read(node, Company.class);
            if (company.active()) {
                found.put(id, company);
            }
        });
        return found;
    }

    private <T> T read(JsonNode node, Class<T> type) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Estado replicado de ms-users ilegible", e);
        }
    }

    static boolean sameCity(String a, String b) {
        return a != null && b != null && normalize(a).equals(normalize(b));
    }

    private static String normalize(String value) {
        String withoutAccents = Normalizer.normalize(value.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return withoutAccents.toLowerCase(Locale.ROOT);
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
