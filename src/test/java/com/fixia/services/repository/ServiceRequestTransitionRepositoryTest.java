package com.fixia.services.repository;

import com.fixia.services.domain.ActorRole;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.domain.ServiceRequestTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Historial de transiciones contra las migraciones reales de Flyway (H2 en modo PostgreSQL), GC-437. */
@SpringBootTest
@ActiveProfiles("test")
class ServiceRequestTransitionRepositoryTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();

    @Autowired
    private ServiceRequestRepository requestRepository;

    @Autowired
    private ServiceRequestTransitionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        requestRepository.deleteAllInBatch();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private ServiceRequest savedRequest() {
        Instant now = now();
        return requestRepository.saveAndFlush(ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, now.plus(Duration.ofDays(2)),
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), now));
    }

    private ServiceRequestTransition transition(UUID requestId, ServiceRequestStatus from, ServiceRequestStatus to,
                                                ActorRole role, Instant at) {
        return repository.saveAndFlush(ServiceRequestTransition.of(requestId, from, to,
                role == ActorRole.PROFESSIONAL ? PROVIDER : CLIENT, role, null, at));
    }

    @Test
    void guardaYRecuperaElHistorialEnOrdenCronologicoAunqueSeGuardeDesordenado() {
        UUID id = savedRequest().getId();
        Instant base = now();
        transition(id, ServiceRequestStatus.CONTRACTED, ServiceRequestStatus.IN_PROGRESS, ActorRole.PROFESSIONAL,
                base.plusSeconds(20));
        transition(id, null, ServiceRequestStatus.PUBLISHED, ActorRole.CLIENT, base);
        transition(id, ServiceRequestStatus.PROVIDER_SELECTED, ServiceRequestStatus.CONTRACTED, ActorRole.CLIENT,
                base.plusSeconds(10));

        List<ServiceRequestTransition> timeline = repository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(id);

        assertThat(timeline).extracting(ServiceRequestTransition::getToStatus).containsExactly(
                ServiceRequestStatus.PUBLISHED, ServiceRequestStatus.CONTRACTED, ServiceRequestStatus.IN_PROGRESS);
        assertThat(timeline.get(0).getFromStatus()).isNull();
        assertThat(timeline.get(2).getActorId()).isEqualTo(PROVIDER);
        assertThat(timeline.get(2).getActorRole()).isEqualTo(ActorRole.PROFESSIONAL);
        assertThat(timeline.get(2).getOccurredAt()).isEqualTo(base.plusSeconds(20));
    }

    @Test
    void cadaSolicitudSoloVeSuPropioHistorial() {
        UUID first = savedRequest().getId();
        UUID second = savedRequest().getId();
        transition(first, null, ServiceRequestStatus.PUBLISHED, ActorRole.CLIENT, now());

        assertThat(repository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(first)).hasSize(1);
        assertThat(repository.findByServiceRequestIdOrderByOccurredAtAscIdAsc(second)).isEmpty();
    }

    @Test
    void borrarLaSolicitudBorraSuHistorial() {
        UUID id = savedRequest().getId();
        transition(id, null, ServiceRequestStatus.PUBLISHED, ActorRole.CLIENT, now());

        requestRepository.deleteAllInBatch();

        assertThat(repository.count()).isZero();
    }

    @Test
    void laBaseNoAceptaHistorialDeUnaSolicitudInexistente() {
        assertThatThrownBy(() -> transition(UUID.randomUUID(), null, ServiceRequestStatus.PUBLISHED, ActorRole.CLIENT,
                now())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laBaseExigeUnRolValidoYUnActorSalvoElSistema() {
        UUID id = savedRequest().getId();
        String insert = "INSERT INTO service_request_transitions (id, service_request_id, from_status, to_status, "
                + "actor_id, actor_role, occurred_at) VALUES (?, ?, NULL, 'PUBLISHED', ?, ?, CURRENT_TIMESTAMP)";

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), id, CLIENT, "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), id, null, "CLIENT"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.update(insert, UUID.randomUUID(), id, null, "SYSTEM")).isEqualTo(1);
        assertThat(jdbcTemplate.update(insert, UUID.randomUUID(), id, CLIENT, "CLIENT")).isEqualTo(1);
    }
}
