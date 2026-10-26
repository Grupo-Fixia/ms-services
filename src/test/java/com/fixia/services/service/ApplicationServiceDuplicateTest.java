package com.fixia.services.service;

import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.exception.DuplicateApplicationException;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Último recurso contra el duplicado (GC-343): si dos peticiones pasan las comprobaciones a la vez, la restricción
 * única de la base rechaza la segunda y el servicio la traduce al mismo 409. Cualquier otra violación no se oculta.
 */
class ApplicationServiceDuplicateTest {

    private static final BigDecimal PRICE = new BigDecimal("150000");

    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");
    private static final UUID TECHNICIAN = UUID.randomUUID();

    private final ServiceRequestRepository requests = mock(ServiceRequestRepository.class);
    private final ServiceRequestApplicationRepository applications = mock(ServiceRequestApplicationRepository.class);
    private final TechnicianEligibilityClient eligibility = mock(TechnicianEligibilityClient.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final BusinessEventRecorder events = mock(BusinessEventRecorder.class);
    private final ApplicationService service = new ApplicationService(requests, applications, eligibility, events,
            transactions, Clock.fixed(NOW, ZoneOffset.UTC));

    private ServiceRequest request;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        request = ServiceRequest.publish(UUID.randomUUID(), ServiceCategory.PLUMBING, "Fuga",
                new ApproximateLocation("Bogotá", "Chapinero"), null, NOW.plus(Duration.ofDays(2)), List.of(),
                NOW.minusSeconds(60));
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(applications.findByServiceRequestIdAndProviderId(any(), any())).thenReturn(Optional.empty());
        when(eligibility.check(any(), any()))
                .thenReturn(new TechnicianEligibilityClient.Result(request.getId(), true, List.of()));
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<ServiceRequestApplication>) invocation.getArgument(0)).doInTransaction(null));
    }

    private static DataIntegrityViolationException violation(String message) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(message));
    }

    @Test
    void laViolacionDeLaRestriccionUnicaSeTraduceAPostulacionDuplicada() {
        when(applications.saveAndFlush(any())).thenThrow(violation(
                "duplicate key value violates unique constraint \"uk_applications\""));

        assertThatThrownBy(() -> service.apply(TECHNICIAN, request.getId(), PRICE, CurrencyCode.COP))
                .isInstanceOf(DuplicateApplicationException.class)
                .hasMessage("Ya te postulaste a esta solicitud");
    }

    @Test
    void otraViolacionDeIntegridadNoSeConfundeConUnDuplicado() {
        DataIntegrityViolationException other = violation("violates check constraint \"ck_applications_status\"");
        when(applications.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> service.apply(TECHNICIAN, request.getId(), PRICE, CurrencyCode.COP)).isSameAs(other);
    }

    @Test
    void reconoceLaRestriccionSinImportarMayusculasYTolerUnMensajeVacio() {
        assertThat(ApplicationService.isDuplicate(violation("Unique index violation: PUBLIC.UK_APPLICATIONS"))).isTrue();
        assertThat(ApplicationService.isDuplicate(new DataIntegrityViolationException(null))).isFalse();
    }
}
