package com.fixia.services.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.client.TechnicianEligibilityClient;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Validación de postulaciones duplicadas (GC-343): un técnico se postula una sola vez a cada solicitud. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DuplicateApplicationTest {

    private static final String BASE = "/api/services/applications";
    private static final UUID TECHNICIAN = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private TechnicianEligibilityClient eligibilityClient;

    @BeforeEach
    void setUp() {
        serviceRequestRepository.deleteAllInBatch();
        when(eligibilityClient.check(any(), any()))
                .thenReturn(new TechnicianEligibilityClient.Result(UUID.randomUUID(), true, List.of()));
    }

    private ServiceRequest publishedRequest() {
        Instant now = Instant.now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(UUID.randomUUID(),
                ServiceCategory.PLUMBING, "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero"),
                null, now.plus(Duration.ofDays(2)), List.of(), now));
    }

    private ResultActions apply(UUID technician, UUID serviceRequestId) throws Exception {
        return mockMvc.perform(post(BASE)
                .with(jwt().jwt(token -> token.subject("usr_" + technician))
                        .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceRequestId\":\"" + serviceRequestId + "\"}"));
    }

    private UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .get("id").asText());
    }

    @Test
    void unaSegundaPostulacionResponde409ConLaExistenteSinConsultarDeNuevoAMsUsers() throws Exception {
        ServiceRequest request = publishedRequest();
        UUID first = idOf(apply(TECHNICIAN, request.getId()).andExpect(status().isCreated()));

        apply(TECHNICIAN, request.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Postulación duplicada"))
                .andExpect(jsonPath("$.detail").value("Ya te postulaste a esta solicitud"))
                .andExpect(jsonPath("$.applicationId").value(first.toString()));

        verify(eligibilityClient, times(1)).check(any(), any());
        assertThat(applicationRepository.count()).isEqualTo(1);
    }

    @Test
    void elMismoTecnicoPuedePostularseADistintasSolicitudesYVariosTecnicosALaMisma() throws Exception {
        ServiceRequest first = publishedRequest();
        ServiceRequest second = publishedRequest();
        UUID otherTechnician = UUID.randomUUID();

        apply(TECHNICIAN, first.getId()).andExpect(status().isCreated());
        apply(TECHNICIAN, second.getId()).andExpect(status().isCreated());
        apply(otherTechnician, first.getId()).andExpect(status().isCreated());

        assertThat(applicationRepository.count()).isEqualTo(3);
    }

    @Test
    void variasPeticionesSimultaneasDelMismoTecnicoDejanUnaSolaPostulacion() throws Exception {
        ServiceRequest request = publishedRequest();
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> pending = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return apply(TECHNICIAN, request.getId()).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : pending) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(code -> code == 201)).hasSize(1);
        assertThat(applicationRepository.count()).isEqualTo(1);
    }

    @Test
    void laBaseImpideDosPostulacionesDelMismoTecnicoALaMismaSolicitud() throws Exception {
        ServiceRequest request = publishedRequest();
        apply(TECHNICIAN, request.getId()).andExpect(status().isCreated());

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO applications (id, service_request_id, "
                        + "provider_id, status, created_at, updated_at, version) VALUES (?, ?, ?, 'APPLIED', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)", UUID.randomUUID(), request.getId(), TECHNICIAN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_applications");
    }
}
