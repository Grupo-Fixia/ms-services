package com.fixia.services.repository;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistencia de la cotización contra las migraciones reales de Flyway (H2 en modo PostgreSQL). Cada cambio de
 * estado corre en su propia transacción, como lo hará el servicio.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuotationRepositoryTest {

    private static final UUID PROVIDER = UUID.randomUUID();

    @Autowired
    private QuotationRepository quotationRepository;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        // DELETE inmediato; el borrado en cascada se lleva cotizaciones y fotos.
        serviceRequestRepository.deleteAllInBatch();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private ServiceRequest selectedRequest() {
        Instant now = now();
        ServiceRequest request = serviceRequestRepository.saveAndFlush(ServiceRequest.publish(UUID.randomUUID(),
                ServiceCategory.PLUMBING, "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero"),
                null, now.plus(Duration.ofDays(5)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now));
        return transactionTemplate.execute(status -> {
            ServiceRequest found = serviceRequestRepository.findById(request.getId()).orElseThrow();
            found.selectProvider(PROVIDER, now());
            return found;
        });
    }

    private Quotation send(ServiceRequest request, Instant now) {
        return quotationRepository.saveAndFlush(Quotation.send(request, PROVIDER, new BigDecimal("350000.5"),
                CurrencyCode.COP, "Cambio de tubería", "Incluye materiales", now.plus(Duration.ofDays(3)),
                now.plus(Duration.ofDays(1)), now));
    }

    @Test
    void guardaLaCotizacionConTodosSusDatos() {
        ServiceRequest request = selectedRequest();
        Quotation saved = send(request, now());

        Quotation found = quotationRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getServiceRequestId()).isEqualTo(request.getId());
        assertThat(found.getProviderId()).isEqualTo(PROVIDER);
        assertThat(found.getAmount()).isEqualByComparingTo("350000.50");
        assertThat(found.getCurrency()).isEqualTo(CurrencyCode.COP);
        assertThat(found.getScope()).isEqualTo("Cambio de tubería");
        assertThat(found.getConditions()).isEqualTo("Incluye materiales");
        assertThat(found.getAgreedAt()).isEqualTo(saved.getAgreedAt());
        assertThat(found.getValidUntil()).isEqualTo(saved.getValidUntil());
        assertThat(found.getStatus()).isEqualTo(QuotationStatus.SENT);
        assertThat(found.getVersion()).isZero();
    }

    @Test
    void persisteLaAceptacionConSuFecha() {
        UUID id = send(selectedRequest(), now()).getId();
        Instant respondedAt = now();

        transactionTemplate.executeWithoutResult(status ->
                quotationRepository.findById(id).orElseThrow().accept(respondedAt));

        Quotation found = quotationRepository.findById(id).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
        assertThat(found.getRespondedAt()).isEqualTo(respondedAt);
        assertThat(found.getVersion()).isEqualTo(1L);
    }

    @Test
    void listaElHistorialDeLaSolicitudYEncuentraLaVigente() {
        ServiceRequest request = selectedRequest();
        Instant base = now();
        Quotation first = send(request, base.minusSeconds(120));
        transactionTemplate.executeWithoutResult(status ->
                quotationRepository.findById(first.getId()).orElseThrow().supersede(now()));
        Quotation second = send(request, base);
        send(selectedRequest(), base);

        assertThat(quotationRepository.findByServiceRequestIdOrderByCreatedAtDesc(request.getId()))
                .extracting(Quotation::getId).containsExactly(second.getId(), first.getId());
        assertThat(quotationRepository.findFirstByServiceRequestIdAndStatusOrderByCreatedAtDesc(
                request.getId(), QuotationStatus.SENT)).map(Quotation::getId).contains(second.getId());
        assertThat(quotationRepository.findFirstByServiceRequestIdAndStatusOrderByCreatedAtDesc(
                request.getId(), QuotationStatus.ACCEPTED)).isEmpty();
    }

    @Test
    void rechazaLaActualizacionConcurrenteConBloqueoOptimista() {
        UUID id = send(selectedRequest(), now()).getId();
        Quotation first = quotationRepository.findById(id).orElseThrow();
        Quotation second = quotationRepository.findById(id).orElseThrow();

        first.reject(now());
        quotationRepository.saveAndFlush(first);
        second.accept(now());

        assertThatThrownBy(() -> quotationRepository.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(quotationRepository.findById(id).orElseThrow().getStatus()).isEqualTo(QuotationStatus.REJECTED);
    }

    @Test
    void borrarLaSolicitudBorraSusCotizaciones() {
        send(selectedRequest(), now());
        assertThat(quotationRepository.count()).isEqualTo(1);

        serviceRequestRepository.deleteAllInBatch();

        assertThat(quotationRepository.count()).isZero();
    }

    @Test
    void laBaseExigeUnaSolicitudExistente() {
        assertThatThrownBy(() -> jdbcTemplate.update(insertSql(), UUID.randomUUID(), UUID.randomUUID(), PROVIDER,
                "100.00", "COP", "SENT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laBaseRechazaValorMonedaEstadoOVigenciaInvalidos() {
        UUID request = selectedRequest().getId();

        assertThatThrownBy(() -> insert(request, "0", "COP", "SENT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(request, "100", "USD", "SENT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(request, "100", "COP", "DRAFT", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(request, "100", "COP", "ACCEPTED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO quotations (id, service_request_id, provider_id, "
                        + "amount, currency, scope, conditions, agreed_at, valid_until, status, created_at, updated_at, "
                        + "version) VALUES (?, ?, ?, 100, 'COP', 'a', 'b', ?, ?, 'SENT', ?, ?, 0)",
                UUID.randomUUID(), request, PROVIDER, ts(now()), ts(now().plusSeconds(60)), ts(now()), ts(now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insert(request, "100", "COP", "ACCEPTED", ts(now()))).isEqualTo(1);
    }

    private int insert(UUID request, String amount, String currency, String status, java.sql.Timestamp respondedAt) {
        return jdbcTemplate.update(insertSql(), UUID.randomUUID(), request, PROVIDER, amount, currency, status,
                respondedAt);
    }

    private String insertSql() {
        Instant now = now();
        return "INSERT INTO quotations (id, service_request_id, provider_id, amount, currency, scope, conditions, "
                + "agreed_at, valid_until, status, responded_at, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, CAST(? AS NUMERIC(12, 2)), ?, 'a', 'b', TIMESTAMP WITH TIME ZONE '"
                + now.plus(Duration.ofDays(3)) + "', TIMESTAMP WITH TIME ZONE '" + now.plus(Duration.ofDays(1))
                + "', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)";
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }
}
