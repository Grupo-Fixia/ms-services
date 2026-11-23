package com.fixia.services.replication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Compensación de GC-547: cuando la réplica dice que un técnico dejó su empresa, sus cotizaciones enviadas en nombre de
 * esa empresa vencen, aunque sigan vigentes; las de otra empresa o ya respondidas no se tocan.
 */
class CompanyDepartureCompensationTest {

    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final QuotationRepository quotations = mock(QuotationRepository.class);
    private final ServiceContractRepository contracts = mock(ServiceContractRepository.class);
    private final CompanyDepartureCompensation compensation = new CompanyDepartureCompensation(quotations, contracts,
            mock(ServiceRequestRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID technician = UUID.randomUUID();
    private final UUID company = UUID.randomUUID();

    private ObjectNode state(UUID companyAdmin, boolean active) {
        ObjectNode data = mapper.createObjectNode();
        data.put("userId", technician.toString());
        data.put("active", active);
        if (companyAdmin == null) {
            data.putNull("companyAdminUserId");
        } else {
            data.put("companyAdminUserId", companyAdmin.toString());
        }
        return data;
    }

    private static Quotation quotation(UUID provider, QuotationStatus status) {
        Quotation quotation = mock(Quotation.class);
        when(quotation.getProviderId()).thenReturn(provider);
        when(quotation.getStatus()).thenReturn(status);
        return quotation;
    }

    @Test
    void alSalirDeLaEmpresaVencenSusCotizacionesEnviadasEnSuNombre() {
        Quotation sentForCompany = quotation(company, QuotationStatus.SENT);
        Quotation sentForOther = quotation(UUID.randomUUID(), QuotationStatus.SENT);
        Quotation accepted = quotation(company, QuotationStatus.ACCEPTED);
        when(quotations.findByAuthorIdOrderByCreatedAtDescIdDesc(technician))
                .thenReturn(List.of(sentForCompany, sentForOther, accepted));

        compensation.onChange(new StateEvent(UserReplica.TECHNICIAN_STATE, technician, 2, false, state(null, true)),
                state(company, true), false);

        verify(sentForCompany).expireBecauseAuthorLeft(NOW);
        verify(sentForOther, never()).expireBecauseAuthorLeft(any());
        verify(accepted, never()).expireBecauseAuthorLeft(any());
    }

    @Test
    void siSigueEnLaEmpresaNoSeTocaNada() {
        compensation.onChange(new StateEvent(UserReplica.TECHNICIAN_STATE, technician, 2, false,
                state(company, true)), state(company, true), false);

        verify(quotations, never()).findByAuthorIdOrderByCreatedAtDescIdDesc(any());
    }

    @Test
    void otrosTiposDeEstadoSeIgnoran() {
        compensation.onChange(new StateEvent(UserReplica.COMPANY_STATE, company, 2, false, state(null, true)),
                state(company, true), false);

        verify(quotations, never()).findByAuthorIdOrderByCreatedAtDescIdDesc(any());
    }
}
