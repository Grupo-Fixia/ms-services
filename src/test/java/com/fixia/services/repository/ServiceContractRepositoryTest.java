package com.fixia.services.repository;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Persistencia de la contratación contra las migraciones reales de Flyway (H2 en modo PostgreSQL): una por solicitud y
 * por cotización, copia exacta de lo acordado y borrado en cascada con la solicitud.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceContractRepositoryTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();

    @Autowired
    private ServiceContractRepository contractRepository;

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
        // DELETE inmediato; el borrado en cascada se lleva cotizaciones, fotos y contrataciones.
        serviceRequestRepository.deleteAllInBatch();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Solicitud contratada con una cotización aceptada, todo guardado. */
    private record Stored(ServiceRequest request, Quotation quotation) {
    }

    private Stored contractedRequest(UUID provider) {
        Instant now = now();
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga de agua",
                new ApproximateLocation("Bogotá", "Chapinero"), new ExactLocation("Calle 10 # 5-20"),
                now.plus(Duration.ofDays(5)), List.of(), now);
        request.selectProvider(provider, now);
        ServiceRequest savedRequest = serviceRequestRepository.saveAndFlush(request);
        Quotation quotation = Quotation.send(savedRequest, provider, new BigDecimal("90000.50"), CurrencyCode.COP,
                "Cambio de empaque", "Pago al terminar", now.plus(Duration.ofHours(2)), now.plus(Duration.ofHours(1)),
                now);
        quotation.accept(now);
        Quotation savedQuotation = quotationRepository.saveAndFlush(quotation);
        savedRequest.contract(now);
        return new Stored(serviceRequestRepository.saveAndFlush(savedRequest), savedQuotation);
    }

    private ServiceContract formalized(Stored stored) {
        return contractRepository.saveAndFlush(
                ServiceContract.formalize(stored.request(), stored.quotation(), now()));
    }

    @Test
    void laContratacionGuardaLaCopiaExactaDeLoAcordado() {
        Stored stored = contractedRequest(PROVIDER);

        ServiceContract saved = formalized(stored);

        ServiceContract found = contractRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getServiceRequestId()).isEqualTo(stored.request().getId());
        assertThat(found.getQuotationId()).isEqualTo(stored.quotation().getId());
        assertThat(found.getClientId()).isEqualTo(CLIENT);
        assertThat(found.getProviderId()).isEqualTo(PROVIDER);
        assertThat(found.getAmount()).isEqualByComparingTo("90000.50");
        assertThat(found.getCurrency()).isEqualTo(CurrencyCode.COP);
        assertThat(found.getScope()).isEqualTo("Cambio de empaque");
        assertThat(found.getConditions()).isEqualTo("Pago al terminar");
        assertThat(found.getAgreedAt()).isEqualTo(stored.quotation().getAgreedAt());
        assertThat(found.getContractedAt()).isNotNull();
    }

    @Test
    void sePuedeBuscarPorSolicitudYPorPrestador() {
        Stored stored = contractedRequest(PROVIDER);
        ServiceContract saved = formalized(stored);

        assertThat(contractRepository.findByServiceRequestId(stored.request().getId())).contains(saved);
        assertThat(contractRepository.findByIdAndProviderId(saved.getId(), PROVIDER)).contains(saved);
        assertThat(contractRepository.findByIdAndProviderId(saved.getId(), UUID.randomUUID())).isEmpty();
        assertThat(contractRepository.findByServiceRequestId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void lasContratacionesDelPrestadorSalenLaMasRecientePrimero() {
        Stored first = contractedRequest(PROVIDER);
        Stored second = contractedRequest(PROVIDER);
        contractedRequest(UUID.randomUUID());
        Instant base = now();
        ServiceContract older = contractRepository.saveAndFlush(
                ServiceContract.formalize(first.request(), first.quotation(), base.minusSeconds(60)));
        ServiceContract newer = contractRepository.saveAndFlush(
                ServiceContract.formalize(second.request(), second.quotation(), base));

        assertThat(contractRepository.findByProviderIdOrderByContractedAtDescIdDesc(PROVIDER))
                .containsExactly(newer, older);
    }

    @Test
    void unaSolicitudSoloTieneUnaContratacion() {
        Stored stored = contractedRequest(PROVIDER);
        formalized(stored);

        assertThatThrownBy(() -> formalized(stored)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(contractRepository.count()).isEqualTo(1);
    }

    @Test
    void laBaseExigeUnValorPositivoYMonedaConocida() {
        Stored stored = contractedRequest(PROVIDER);
        ServiceContract saved = formalized(stored);

        assertThatThrownBy(() -> jdbcTemplate.update("update service_contracts set amount = 0 where id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("update service_contracts set currency = 'USD' where id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void borrarLaSolicitudBorraSuContratacion() {
        Stored stored = contractedRequest(PROVIDER);
        formalized(stored);

        transactionTemplate.executeWithoutResult(status -> serviceRequestRepository.deleteById(stored.request().getId()));
        serviceRequestRepository.flush();

        assertThat(contractRepository.count()).isZero();
    }

    @Test
    void laTablaNoTieneColumnasDeSaldoRetencionNiPago() {
        List<String> columns = jdbcTemplate.queryForList(
                "select lower(column_name) from information_schema.columns where lower(table_name) = 'service_contracts'",
                String.class);

        assertThat(columns).contains("amount", "currency", "scope", "conditions", "agreed_at", "contracted_at");
        assertThat(columns).noneMatch(column -> column.contains("balance") || column.contains("saldo")
                || column.contains("withdraw") || column.contains("retenci") || column.contains("payment")
                || column.contains("status"));
    }
}
