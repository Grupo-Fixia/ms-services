package com.fixia.services.repository;

import com.fixia.services.domain.ApplicationStatus;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistencia de la postulación contra las migraciones reales de Flyway (H2 en modo PostgreSQL). Cada cambio de
 * estado corre en su propia transacción, como lo hará el servicio.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceRequestApplicationRepositoryTest {

    private static final UUID PROVIDER = UUID.randomUUID();

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        // DELETE inmediato; el borrado en cascada se lleva postulaciones y fotos.
        serviceRequestRepository.deleteAllInBatch();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private ServiceRequest publishedRequest() {
        Instant now = now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(UUID.randomUUID(),
                ServiceCategory.PLUMBING, "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero"),
                null, now.plus(Duration.ofDays(2)), List.of(), now));
    }

    private ServiceRequestApplication apply(ServiceRequest request, UUID provider, Instant now) {
        return applicationRepository.saveAndFlush(ServiceRequestApplication.submit(request, provider, now));
    }

    @Test
    void guardaLaPostulacionEnEstadoPostulada() {
        ServiceRequest request = publishedRequest();
        ServiceRequestApplication saved = apply(request, PROVIDER, now());

        ServiceRequestApplication found = applicationRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getServiceRequestId()).isEqualTo(request.getId());
        assertThat(found.getProviderId()).isEqualTo(PROVIDER);
        assertThat(found.getStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(found.getVersion()).isZero();
    }

    @Test
    void listaLasDelTecnicoLaMasRecientePrimeroYLasDeUnaSolicitudEnOrdenDeLlegada() {
        ServiceRequest first = publishedRequest();
        ServiceRequest second = publishedRequest();
        Instant base = now();
        ServiceRequestApplication older = apply(first, PROVIDER, base.minusSeconds(60));
        ServiceRequestApplication newer = apply(second, PROVIDER, base);
        ServiceRequestApplication fromOther = apply(first, UUID.randomUUID(), base.minusSeconds(30));

        assertThat(applicationRepository.findByProviderIdOrderByCreatedAtDesc(PROVIDER))
                .extracting(ServiceRequestApplication::getId).containsExactly(newer.getId(), older.getId());
        assertThat(applicationRepository.findByServiceRequestIdOrderByCreatedAtAsc(first.getId()))
                .extracting(ServiceRequestApplication::getId).containsExactly(older.getId(), fromOther.getId());
        assertThat(applicationRepository.findByIdAndProviderId(older.getId(), PROVIDER)).isPresent();
        assertThat(applicationRepository.findByIdAndProviderId(fromOther.getId(), PROVIDER)).isEmpty();
    }

    @Test
    void persisteLaSeleccionYAvanzaLaVersion() {
        UUID id = apply(publishedRequest(), PROVIDER, now()).getId();

        transactionTemplate.executeWithoutResult(status ->
                applicationRepository.findById(id).orElseThrow().select(now()));

        ServiceRequestApplication found = applicationRepository.findById(id).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ApplicationStatus.SELECTED);
        assertThat(found.getVersion()).isEqualTo(1L);
    }

    @Test
    void laSolicitudSePuedeLeerBloqueadaDentroDeUnaTransaccion() {
        UUID id = publishedRequest().getId();

        ServiceRequest locked = transactionTemplate.execute(status ->
                serviceRequestRepository.findByIdForUpdate(id).orElseThrow());

        assertThat(locked.getId()).isEqualTo(id);
        Optional<ServiceRequest> missing = transactionTemplate.execute(status ->
                serviceRequestRepository.findByIdForUpdate(UUID.randomUUID()));
        assertThat(missing).isEmpty();
    }

    @Test
    void borrarLaSolicitudBorraSusPostulaciones() {
        apply(publishedRequest(), PROVIDER, now());
        assertThat(applicationRepository.count()).isEqualTo(1);

        serviceRequestRepository.deleteAllInBatch();

        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void laBaseRechazaUnEstadoInvalidoOUnaSolicitudInexistente() {
        UUID request = publishedRequest().getId();
        String insert = "INSERT INTO service_request_applications (id, service_request_id, provider_id, status, "
                + "created_at, updated_at, version) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)";

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), request, PROVIDER, "WITHDRAWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID(), UUID.randomUUID(), PROVIDER, "APPLIED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.update(insert, UUID.randomUUID(), request, PROVIDER, "APPLIED")).isEqualTo(1);
    }
}
