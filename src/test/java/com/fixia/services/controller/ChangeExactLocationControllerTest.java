package com.fixia.services.controller;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GC-514: el cliente corrige la dirección exacta de su solicitud mientras no esté contratada. La dirección sigue sin
 * salir en la respuesta antes de contratar y nunca viaja en eventos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChangeExactLocationControllerTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final String NEW_ADDRESS = "Carrera 7 # 45-10, apto 502";

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

    @Autowired
    private ServiceRequestTransitionRepository transitionRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAllInBatch();
        transitionRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    private ServiceRequest published(UUID clientId, ExactLocation exact) {
        Instant now = Instant.now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(clientId, ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero"), exact,
                now.plus(Duration.ofDays(5)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now));
    }

    private static RequestPostProcessor client(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private ResultActions change(UUID clientId, UUID requestId, String body) throws Exception {
        return mockMvc.perform(patch("/api/services/requests/" + requestId + "/exact-location").with(client(clientId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(String address, Double latitude, Double longitude) {
        return """
                {"address":"%s","latitude":%s,"longitude":%s}
                """.formatted(address, latitude, longitude);
    }

    /** La dirección guardada, leída como la verá el prestador una vez contratada. */
    private ExactLocation storedAfterContract(UUID requestId) {
        return transactions.execute(status -> {
            ServiceRequest request = serviceRequestRepository.findById(requestId).orElseThrow();
            request.selectProvider(PROVIDER, Instant.now());
            request.contract(Instant.now());
            return request.getExactLocation().orElseThrow();
        });
    }

    @Test
    void elClienteCambiaLaDireccionDeUnaSolicitudPublicada() throws Exception {
        ServiceRequest request = published(CLIENT, new ExactLocation("Calle 10 # 5-20", 4.65, -74.08));

        change(CLIENT, request.getId(), body("  " + NEW_ADDRESS + "  ", 4.6301, -74.0652))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.exactLocation").doesNotExist());

        ExactLocation stored = storedAfterContract(request.getId());
        assertThat(stored.address()).isEqualTo(NEW_ADDRESS);
        assertThat(stored.latitude()).isEqualTo(4.6301);
        assertThat(stored.longitude()).isEqualTo(-74.0652);
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    @Test
    void sePuedeAgregarLaDireccionSiNoSeIndicoAlPublicarYTambienConPrestadorElegido() throws Exception {
        ServiceRequest request = published(CLIENT, null);
        request.selectProvider(PROVIDER, Instant.now());
        serviceRequestRepository.saveAndFlush(request);

        change(CLIENT, request.getId(), body(NEW_ADDRESS, null, null)).andExpect(status().isOk());

        ExactLocation stored = transactions.execute(status -> {
            ServiceRequest saved = serviceRequestRepository.findById(request.getId()).orElseThrow();
            saved.contract(Instant.now());
            return saved.getExactLocation().orElseThrow();
        });
        assertThat(stored.address()).isEqualTo(NEW_ADDRESS);
        assertThat(stored.latitude()).isNull();
    }

    @Test
    void despuesDeContratarNoSePuedeCambiar() throws Exception {
        ServiceRequest request = published(CLIENT, new ExactLocation("Calle 10 # 5-20", null, null));
        request.selectProvider(PROVIDER, Instant.now());
        request.contract(Instant.now());
        serviceRequestRepository.saveAndFlush(request);

        change(CLIENT, request.getId(), body(NEW_ADDRESS, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Solo se puede cambiar la dirección exacta antes de contratar"));

        assertThat(serviceRequestRepository.findById(request.getId()).orElseThrow().getExactLocation()
                .orElseThrow().address()).isEqualTo("Calle 10 # 5-20");
    }

    @Test
    void datosInvalidosResponden400() throws Exception {
        ServiceRequest request = published(CLIENT, null);

        change(CLIENT, request.getId(), body("   ", null, null)).andExpect(status().isBadRequest());
        change(CLIENT, request.getId(), body(NEW_ADDRESS, 4.6, null)).andExpect(status().isBadRequest());
        change(CLIENT, request.getId(), body(NEW_ADDRESS, 91.0, -74.0)).andExpect(status().isBadRequest());
        change(CLIENT, request.getId(), body("x".repeat(256), null, null)).andExpect(status().isBadRequest());
    }

    @Test
    void unaSolicitudAjenaOInexistenteRespondeLoMismo() throws Exception {
        ServiceRequest request = published(CLIENT, null);

        change(OTHER_CLIENT, request.getId(), body(NEW_ADDRESS, null, null)).andExpect(status().isNotFound());
        change(CLIENT, UUID.randomUUID(), body(NEW_ADDRESS, null, null)).andExpect(status().isNotFound());
    }

    @Test
    void unTecnicoNoPuedeCambiarla() throws Exception {
        ServiceRequest request = published(CLIENT, null);

        mockMvc.perform(patch("/api/services/requests/" + request.getId() + "/exact-location")
                        .with(jwt().jwt(token -> token.subject("usr_" + PROVIDER))
                                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(NEW_ADDRESS, null, null)))
                .andExpect(status().isForbidden());
    }
}
