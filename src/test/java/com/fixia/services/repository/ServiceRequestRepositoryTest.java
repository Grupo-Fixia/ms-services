package com.fixia.services.repository;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.domain.ServiceRequestStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistencia de la solicitud de servicio contra las migraciones reales de Flyway (H2 en modo PostgreSQL).
 * Cada cambio de estado corre en su propia transacción, como lo hará el servicio.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceRequestRepositoryTest {

    private static final BigDecimal PRICE = new BigDecimal("150000");

    private static final UUID PROVIDER = UUID.randomUUID();

    @Autowired
    private ServiceRequestRepository repository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        // DELETE inmediato; el borrado en cascada se lleva las fotos.
        repository.deleteAllInBatch();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private ServiceRequest save(UUID clientId, List<String> photos, ExactLocation exact) {
        Instant now = now();
        return repository.saveAndFlush(ServiceRequest.publish(clientId, ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero", 4.6533, -74.0836),
                exact, now.plus(Duration.ofDays(1)), new BigDecimal("200000"), CurrencyCode.COP, photos, now));
    }

    @Test
    void guardaElPresupuestoConDosDecimalesYLoRecuperaIgual() {
        Instant now = now();
        ServiceRequest saved = repository.saveAndFlush(ServiceRequest.publish(UUID.randomUUID(),
                ServiceCategory.PLUMBING, "Fuga de agua", new ApproximateLocation("Bogotá", "Chapinero"), null,
                now.plus(Duration.ofDays(1)), new BigDecimal("98500.5"), CurrencyCode.COP, List.of(), now));

        ServiceRequest found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getBudgetAmount()).isEqualByComparingTo("98500.50");
        assertThat(found.getBudgetCurrency()).isEqualTo(CurrencyCode.COP);
        assertThat(jdbcTemplate.queryForObject("SELECT budget_amount FROM service_requests WHERE id = ?",
                String.class, saved.getId())).isEqualTo("98500.50");
    }

    @Test
    void laBaseAdmiteUnaSolicitudHistoricaSinPresupuestoPeroNoUnoIncompletoOInvalido() {
        UUID id = save(UUID.randomUUID(), List.of(), null).getId();

        jdbcTemplate.update("UPDATE service_requests SET budget_amount = NULL, budget_currency = NULL WHERE id = ?",
                id);
        ServiceRequest historic = repository.findById(id).orElseThrow();
        assertThat(historic.getBudgetAmount()).isNull();
        assertThat(historic.getBudgetCurrency()).isNull();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE service_requests SET budget_amount = 1000 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE service_requests SET budget_currency = 'COP' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE service_requests SET budget_amount = 0, budget_currency = 'COP' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE service_requests SET budget_amount = 1000, budget_currency = 'USD' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void guardaLaSolicitudConSusDatosYFotosEnOrden() {
        ServiceRequest saved = save(UUID.randomUUID(), List.of("requests/a.jpg", "requests/b.jpg", "requests/c.jpg"),
                new ExactLocation("Calle 10 # 5-20", 4.7110, -74.0721));

        ServiceRequest found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(ServiceRequestStatus.PUBLISHED);
        assertThat(found.getCategory()).isEqualTo(ServiceCategory.PLUMBING);
        assertThat(found.getDescription()).isEqualTo("Fuga de agua en el baño");
        assertThat(found.getApproximateLocation())
                .isEqualTo(new ApproximateLocation("Bogotá", "Chapinero", 4.65, -74.08));
        assertThat(found.getPhotoKeys()).containsExactly("requests/a.jpg", "requests/b.jpg", "requests/c.jpg");
        assertThat(found.getExpectedAt()).isEqualTo(saved.getExpectedAt());
        assertThat(found.getVersion()).isZero();
    }

    @Test
    void laDireccionExactaSeGuardaPeroSoloSeEntregaContratada() {
        UUID id = save(UUID.randomUUID(), List.of(), new ExactLocation("Calle 10 # 5-20", 4.7110, -74.0721)).getId();

        assertThat(jdbcTemplate.queryForObject("SELECT exact_address FROM service_requests WHERE id = ?",
                String.class, id)).isEqualTo("Calle 10 # 5-20");
        assertThat(repository.findById(id).orElseThrow().getExactLocation()).isEmpty();

        transactionTemplate.executeWithoutResult(status -> {
            ServiceRequest request = repository.findById(id).orElseThrow();
            request.selectProvider(PROVIDER, now());
            request.contract(now());
        });

        assertThat(repository.findById(id).orElseThrow().getExactLocation())
                .hasValueSatisfying(location -> assertThat(location.address()).isEqualTo("Calle 10 # 5-20"));
    }

    @Test
    void persisteLosCambiosDeEstadoYElPrestadorSeleccionado() {
        UUID id = save(UUID.randomUUID(), List.of(), null).getId();

        transactionTemplate.executeWithoutResult(status ->
                repository.findById(id).orElseThrow().selectProvider(PROVIDER, now()));

        ServiceRequest found = repository.findById(id).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
        assertThat(found.getSelectedProviderId()).isEqualTo(PROVIDER);
        assertThat(found.getVersion()).isEqualTo(1L);
    }

    @Test
    void rechazaLaActualizacionConcurrenteConBloqueoOptimista() {
        UUID id = save(UUID.randomUUID(), List.of(), null).getId();
        ServiceRequest first = repository.findById(id).orElseThrow();
        ServiceRequest second = repository.findById(id).orElseThrow();

        first.cancel(now());
        repository.saveAndFlush(first);
        second.selectProvider(PROVIDER, now());

        assertThatThrownBy(() -> repository.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void listaSoloLasSolicitudesDelClienteLasMasRecientesPrimero() {
        UUID client = UUID.randomUUID();
        Instant base = now();
        ServiceRequest older = repository.saveAndFlush(ServiceRequest.publish(client, ServiceCategory.PAINTING,
                "Pintar sala", new ApproximateLocation("Cali", "Granada"), null, base.plus(Duration.ofDays(2)),
                new BigDecimal("200000"), CurrencyCode.COP,
                List.of("requests/x.jpg"), base.minusSeconds(60)));
        ServiceRequest newer = repository.saveAndFlush(ServiceRequest.publish(client, ServiceCategory.CARPENTRY,
                "Arreglar puerta", new ApproximateLocation("Cali", "Granada"), null, base.plus(Duration.ofDays(2)),
                new BigDecimal("200000"), CurrencyCode.COP,
                List.of(), base));
        save(UUID.randomUUID(), List.of(), null);

        List<ServiceRequest> found = repository.findByClientIdOrderByPublishedAtDesc(client);

        assertThat(found).extracting(ServiceRequest::getId).containsExactly(newer.getId(), older.getId());
        assertThat(found.get(1).getPhotoKeys()).containsExactly("requests/x.jpg");
    }

    @Test
    void borrarLaSolicitudBorraSusFotos() {
        UUID id = save(UUID.randomUUID(), List.of("requests/a.jpg", "requests/b.jpg"), null).getId();
        assertThat(count("service_request_photos")).isEqualTo(2);

        repository.deleteAllInBatch();

        assertThat(repository.findById(id)).isEmpty();
        assertThat(count("service_request_photos")).isZero();
    }

    @Test
    void laBaseRechazaUnEstadoOUnaCategoriaInvalidos() {
        UUID id = save(UUID.randomUUID(), List.of(), null).getId();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE service_requests SET status = 'DONE' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE service_requests SET category = 'GARDENING' WHERE id = ?",
                id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laBaseExigePrestadorCuandoLaSolicitudLoTiene() {
        UUID id = save(UUID.randomUUID(), List.of(), null).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE service_requests SET status = 'PROVIDER_SELECTED' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.update("UPDATE service_requests SET status = 'PROVIDER_SELECTED', "
                + "selected_provider_id = ? WHERE id = ?", PROVIDER, id)).isEqualTo(1);
    }

    @Test
    void laBaseRechazaDosFotosEnLaMismaPosicion() {
        UUID id = save(UUID.randomUUID(), List.of("requests/a.jpg"), null).getId();

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO service_request_photos "
                + "(id, service_request_id, storage_key, sort_order, created_at) VALUES (?, ?, 'otra.jpg', 0, ?)",
                UUID.randomUUID(), id, java.sql.Timestamp.from(now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    // ---------- Candidatas a oportunidad (GC-337) ----------

    private ServiceRequest published(Instant publishedAt, Instant expectedAt) {
        return repository.saveAndFlush(ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.PLUMBING,
                "Fuga de agua", new ApproximateLocation("Bogotá", "Chapinero"), null, expectedAt,
                new BigDecimal("200000"), CurrencyCode.COP, List.of(),
                publishedAt));
    }

    @Test
    void lasCandidatasSonLasPublicadasConFechaPorDelanteLaMasRecientePrimero() {
        Instant now = now();
        ServiceRequest older = published(now.minusSeconds(120), now.plus(Duration.ofDays(1)));
        ServiceRequest newer = published(now.minusSeconds(60), now.plus(Duration.ofDays(1)));
        published(now.minusSeconds(30), now.minusSeconds(1));
        ServiceRequest cancelled = published(now.minusSeconds(20), now.plus(Duration.ofDays(1)));
        jdbcTemplate.update("UPDATE service_requests SET status = 'CANCELLED' WHERE id = ?", cancelled.getId());
        ServiceRequest selected = published(now.minusSeconds(10), now.plus(Duration.ofDays(1)));
        transactionTemplate.executeWithoutResult(tx -> repository.findById(selected.getId()).orElseThrow()
                .selectProvider(UUID.randomUUID(), now));

        Slice<ServiceRequest> found = repository.findOpenWithoutApplicationFrom(PROVIDER, now, PageRequest.of(0, 10));

        assertThat(found.getContent()).extracting(ServiceRequest::getId).containsExactly(newer.getId(), older.getId());
        assertThat(found.hasNext()).isFalse();
    }

    @Test
    void excluyeLasSolicitudesALasQueElTecnicoYaSePostuloPeroNoLasDeOtros() {
        Instant now = now();
        ServiceRequest mine = published(now.minusSeconds(60), now.plus(Duration.ofDays(1)));
        ServiceRequest others = published(now.minusSeconds(30), now.plus(Duration.ofDays(1)));
        applicationRepository.saveAndFlush(ServiceRequestApplication.submit(mine, PROVIDER,
                PRICE, CurrencyCode.COP, now));
        applicationRepository.saveAndFlush(ServiceRequestApplication.submit(others, UUID.randomUUID(),
                PRICE, CurrencyCode.COP, now));

        assertThat(repository.findOpenWithoutApplicationFrom(PROVIDER, now, PageRequest.of(0, 10)).getContent())
                .extracting(ServiceRequest::getId).containsExactly(others.getId());
        assertThat(repository.findOpenWithoutApplicationFrom(UUID.randomUUID(), now, PageRequest.of(0, 10))
                .getContent()).extracting(ServiceRequest::getId).containsExactly(others.getId(), mine.getId());
    }

    @Test
    void laPaginacionPorCursorNoRepiteNiSaltaSolicitudesNiSiquieraConElMismoInstante() {
        Instant now = now();
        // Siete solicitudes: tres comparten instante (desempata el id) y las demás son distintas.
        Instant shared = now.minusSeconds(100);
        List<Instant> publishedAt = List.of(shared, shared, shared, now.minusSeconds(90), now.minusSeconds(80),
                now.minusSeconds(70), now.minusSeconds(60));
        publishedAt.forEach(at -> published(at, now.plus(Duration.ofDays(1))));
        List<ServiceRequest> fullOrder =
                repository.findOpenWithoutApplicationFrom(PROVIDER, now, PageRequest.of(0, 50)).getContent();
        assertThat(fullOrder).hasSize(7);
        assertThat(fullOrder).extracting(ServiceRequest::getPublishedAt).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());

        List<UUID> walked = new ArrayList<>();
        Slice<ServiceRequest> page = repository.findOpenWithoutApplicationFrom(PROVIDER, now, PageRequest.of(0, 2));
        int pages = 1;
        while (true) {
            page.forEach(request -> walked.add(request.getId()));
            if (!page.hasNext()) {
                break;
            }
            ServiceRequest last = page.getContent().get(page.getContent().size() - 1);
            page = repository.findOpenWithoutApplicationAfter(PROVIDER, now, last.getPublishedAt(), last.getId(),
                    PageRequest.of(0, 2));
            pages++;
        }

        assertThat(walked).containsExactlyElementsOf(fullOrder.stream().map(ServiceRequest::getId).toList());
        assertThat(pages).isEqualTo(4);
    }

    @Test
    void despuesDelCursorSoloQuedanLasEstrictamenteAnterioresYSeSiguenAplicandoLasExclusiones() {
        Instant now = now();
        ServiceRequest newest = published(now.minusSeconds(10), now.plus(Duration.ofDays(1)));
        ServiceRequest middle = published(now.minusSeconds(20), now.plus(Duration.ofDays(1)));
        ServiceRequest applied = published(now.minusSeconds(30), now.plus(Duration.ofDays(1)));
        ServiceRequest oldest = published(now.minusSeconds(40), now.plus(Duration.ofDays(1)));
        published(now.minusSeconds(35), now.minusSeconds(1));
        applicationRepository.saveAndFlush(ServiceRequestApplication.submit(applied, PROVIDER,
                PRICE, CurrencyCode.COP, now));

        Slice<ServiceRequest> after = repository.findOpenWithoutApplicationAfter(PROVIDER, now,
                newest.getPublishedAt(), newest.getId(), PageRequest.of(0, 10));

        assertThat(after.getContent()).extracting(ServiceRequest::getId).containsExactly(middle.getId(), oldest.getId());
        assertThat(repository.findOpenWithoutApplicationAfter(PROVIDER, now, oldest.getPublishedAt(), oldest.getId(),
                PageRequest.of(0, 10)).getContent()).isEmpty();
    }
}
