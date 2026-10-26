package com.fixia.services.controller;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.QuotationStatus;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.service.ContractService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * La contratación y la aceptación son una sola transacción (GC-359): si no se puede guardar la contratación, la
 * cotización sigue enviada y la solicitud con su prestador seleccionado; nada queda a medias.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractAtomicityTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private QuotationRepository quotationRepository;

    @Autowired
    private ServiceContractRepository contractRepository;

    @SpyBean
    private ContractService contractService;

    @BeforeEach
    void cleanDatabase() {
        Mockito.reset(contractService);
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    @Test
    void siFallaLaContratacionLaAceptacionSeDeshaceCompleta() {
        Instant now = Instant.now();
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga de agua",
                new ApproximateLocation("Bogotá", "Chapinero"), new ExactLocation("Calle 10 # 5-20"),
                now.plus(Duration.ofDays(5)), List.of(), now);
        request.selectProvider(PROVIDER, now);
        ServiceRequest saved = serviceRequestRepository.saveAndFlush(request);
        Quotation quotation = quotationRepository.saveAndFlush(Quotation.send(saved, PROVIDER,
                new BigDecimal("90000"), CurrencyCode.COP, "Alcance", "Condiciones", now.plus(Duration.ofDays(3)),
                now.plus(Duration.ofDays(1)), now));
        Mockito.doThrow(new IllegalStateException("fallo simulado al guardar la contratación"))
                .when(contractService).formalize(any(), any(), any());

        assertThatThrownBy(() -> mockMvc.perform(post("/api/services/requests/" + saved.getId() + "/quotations/"
                + quotation.getId() + "/acceptance")
                .with(jwt().jwt(token -> token.subject("usr_" + CLIENT))
                        .authorities(new SimpleGrantedAuthority("ROLE_CLIENT")))))
                .hasRootCauseMessage("fallo simulado al guardar la contratación");

        assertThat(quotationRepository.findById(quotation.getId()).orElseThrow().getStatus())
                .isEqualTo(QuotationStatus.SENT);
        assertThat(quotationRepository.findById(quotation.getId()).orElseThrow().getRespondedAt()).isNull();
        assertThat(serviceRequestRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(ServiceRequestStatus.PROVIDER_SELECTED);
        assertThat(contractRepository.count()).isZero();
    }
}
