package com.fixia.services.replication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fixia.services.client.CompanyClient;
import com.fixia.services.client.MatchingClient;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.client.TechnicianProfileClient;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.exception.InvalidMatchingQueryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Las consultas que antes iban a ms-users y ms-matching-geo, ahora contra la réplica local (GC-547): elegibilidad con
 * las reglas portadas, perfiles, pertenencia a empresas y técnicos compatibles. Los estados se cargan como los traería
 * Kafka, por el manejador de la réplica.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserReplicaTest {

    /** Martes 2026-11-24 a las 10:00 en Bogotá. */
    private static final Instant TUESDAY_10AM = Instant.parse("2026-11-24T15:00:00Z");

    @Autowired
    private ReplicatedStateHandler handler;

    @Autowired
    private ReplicatedStateRepository states;

    @Autowired
    private TechnicianEligibilityClient eligibility;

    @Autowired
    private TechnicianProfileClient profiles;

    @Autowired
    private CompanyClient companies;

    @Autowired
    private MatchingClient matching;

    @Autowired
    private ObjectMapper mapper;

    private final UUID ana = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private final UUID beto = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private final UUID carla = UUID.fromString("00000000-0000-4000-8000-00000000000c");
    private final UUID company = UUID.randomUUID();
    private long sequence = 1;

    @BeforeEach
    void setUp() {
        states.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    private ObjectNode technician(UUID id, String city, Double lat, Double lon, Integer radius, UUID companyAdmin) {
        ObjectNode data = mapper.createObjectNode();
        data.put("userId", id.toString());
        data.put("active", true);
        data.put("displayName", "Técnico " + id.toString().substring(35));
        data.put("verificationStatus", "PENDING");
        data.put("verificationRequirementMet", true);
        data.putArray("categories").add("PLUMBING");
        data.put("professionalDescription", "Plomero");
        data.put("yearsOfExperience", 5);
        ObjectNode coverage = data.putObject("coverage");
        coverage.put("city", city);
        if (radius != null) {
            coverage.put("latitude", lat);
            coverage.put("longitude", lon);
            coverage.put("radiusKm", radius);
        }
        ArrayNode slots = data.putArray("availability");
        slots.addObject().put("dayOfWeek", "TUESDAY").put("startTime", "08:00").put("endTime", "12:00");
        if (companyAdmin != null) {
            data.put("companyAdminUserId", companyAdmin.toString());
        } else {
            data.putNull("companyAdminUserId");
        }
        data.putObject("reputation").put("average", 4.5).put("count", 2);
        return data;
    }

    private void apply(String type, UUID key, ObjectNode data) {
        handler.apply(new StateEvent(type, key, sequence++, false, data));
    }

    private void company(boolean active) {
        ObjectNode data = mapper.createObjectNode();
        data.put("companyAdminUserId", company.toString());
        data.put("active", active);
        data.put("name", "Servicios Andina");
        data.putNull("reputation");
        apply(UserReplica.COMPANY_STATE, company, data);
    }

    private static ServiceRequest request(ServiceCategory category, String city, Double lat, Double lon,
                                          Instant expectedAt) {
        ServiceRequest request = mock(ServiceRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        when(request.getCategory()).thenReturn(category);
        when(request.getApproximateLocation()).thenReturn(new ApproximateLocation(city, "Centro", lat, lon));
        when(request.getExpectedAt()).thenReturn(expectedAt);
        return request;
    }

    // ---------- Elegibilidad ----------

    @Test
    void esElegibleConCategoriaZonaYFranja() {
        apply(UserReplica.TECHNICIAN_STATE, ana, technician(ana, "Bogotá", 4.65, -74.05, 5, null));

        TechnicianEligibilityClient.Result result = eligibility.check(ana,
                request(ServiceCategory.PLUMBING, "bogota", 4.66, -74.06, TUESDAY_10AM));

        assertThat(result.eligible()).isTrue();
        assertThat(result.reasons()).isEmpty();
        assertThat(result.isCompanyMember()).isFalse();
    }

    @Test
    void daLosMismosMotivosQueMsUsers() {
        ObjectNode state = technician(ana, "Bogotá", 4.65, -74.05, 1, company);
        state.put("verificationRequirementMet", false);
        apply(UserReplica.TECHNICIAN_STATE, ana, state);

        TechnicianEligibilityClient.Result result = eligibility.check(ana,
                request(ServiceCategory.ELECTRICAL, "Bogotá", 4.75, -74.05, TUESDAY_10AM.plusSeconds(3 * 3600)));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly("VERIFICATION", "CATEGORY", "ZONE", "AVAILABILITY");
        assertThat(result.isCompanyMember()).isTrue();
    }

    @Test
    void unTecnicoQueLaReplicaNoTieneOInactivoNoEsElegible() {
        ObjectNode inactive = technician(beto, "Bogotá", null, null, null, null);
        inactive.put("active", false);
        apply(UserReplica.TECHNICIAN_STATE, beto, inactive);
        ServiceRequest request = request(ServiceCategory.PLUMBING, "Bogotá", null, null, TUESDAY_10AM);

        assertThat(eligibility.check(ana, request).reasons()).containsExactly("TECHNICIAN_NOT_FOUND");
        assertThat(eligibility.check(beto, request).reasons()).containsExactly("TECHNICIAN_NOT_FOUND");
    }

    // ---------- Perfiles ----------

    @Test
    void losPerfilesSalenDeLaReplicaSinElCirculo() {
        apply(UserReplica.TECHNICIAN_STATE, ana, technician(ana, "Medellín", 6.2, -75.5, 10, null));

        TechnicianProfileClient.PublicProfile profile = profiles.findPublicProfiles(List.of(ana, beto)).get(ana);

        assertThat(profiles.findPublicProfiles(List.of(ana, beto))).containsOnlyKeys(ana);
        assertThat(profile.city()).isEqualTo("Medellín");
        assertThat(profile.categories()).containsExactly(ServiceCategory.PLUMBING);
        assertThat(profile.reputation().count()).isEqualTo(2);
    }

    // ---------- Empresas ----------

    @Test
    void laPertenenciaExigeEmpresaYTecnicoActivos() {
        apply(UserReplica.TECHNICIAN_STATE, ana, technician(ana, "Bogotá", null, null, null, company));
        apply(UserReplica.TECHNICIAN_STATE, beto, technician(beto, "Bogotá", null, null, null, null));
        company(true);

        assertThat(companies.isTechnicianAssociated(company, ana)).isTrue();
        assertThat(companies.isTechnicianAssociated(company, beto)).isFalse();
        assertThat(companies.findTechnicians(company)).containsExactly(ana);
        assertThat(companies.findPublicProfiles(List.of(company)).get(company).name())
                .isEqualTo("Servicios Andina");

        company(false);

        assertThat(companies.isTechnicianAssociated(company, ana)).isFalse();
        assertThat(companies.findTechnicians(company)).isEmpty();
        assertThat(companies.findPublicProfiles(List.of(company))).isEmpty();
    }

    @Test
    void unEstadoAtrasadoNoDeshaceUnoMasNuevo() {
        handler.apply(new StateEvent(UserReplica.TECHNICIAN_STATE, ana, 10, false,
                technician(ana, "Bogotá", null, null, null, null)));
        assertThat(handler.apply(new StateEvent(UserReplica.TECHNICIAN_STATE, ana, 9, false,
                technician(ana, "Bogotá", null, null, null, company)))).isEqualTo(ReplicatedStateHandler.Outcome.STALE);
        company(true);

        assertThat(companies.isTechnicianAssociated(company, ana)).isFalse();
    }

    // ---------- Técnicos compatibles ----------

    @Test
    void losCompatiblesSePaginanPorIdConCursor() {
        apply(UserReplica.TECHNICIAN_STATE, ana, technician(ana, "Bogotá", null, null, null, null));
        apply(UserReplica.TECHNICIAN_STATE, beto, technician(beto, "Cali", null, null, null, null));
        apply(UserReplica.TECHNICIAN_STATE, carla, technician(carla, "Bogota", null, null, null, null));
        ServiceRequest request = request(ServiceCategory.PLUMBING, "Bogotá", 4.6, -74.1, TUESDAY_10AM);

        MatchingClient.CompatibleTechnicians first = matching.findCompatible(request, 1, null);
        MatchingClient.CompatibleTechnicians second = matching.findCompatible(request, 1, first.nextCursor());

        assertThat(first.items()).extracting(MatchingClient.CompatibleTechnician::userId).containsExactly(ana);
        assertThat(second.items()).extracting(MatchingClient.CompatibleTechnician::userId).containsExactly(carla);
        assertThat(second.nextCursor()).isNull();
        assertThatThrownBy(() -> matching.findCompatible(request, 1, "no-es-un-cursor"))
                .isInstanceOf(InvalidMatchingQueryException.class);
    }
}
