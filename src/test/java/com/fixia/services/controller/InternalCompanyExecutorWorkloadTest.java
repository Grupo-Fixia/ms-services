package com.fixia.services.controller;

import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ruta interna de trabajo del técnico con su empresa (GC-524, contrato de empresas §6.1): ms-users la consulta antes de
 * dejar que un técnico se desvincule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalCompanyExecutorWorkloadTest {

    private static final String KEY = "clave-interna-de-prueba";
    private static final String PATH = "/internal/services/company-executors/workload";
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TECHNICIAN = UUID.randomUUID();

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
    private OutboxEventRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    // ---------- Utilidades ----------

    private ServiceRequest published() {
        Instant now = Instant.now();
        return serviceRequestRepository.saveAndFlush(ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero", 4.6512, -74.0583),
                new ExactLocation("Calle 10 # 5-20", 4.65, -74.08), now.plus(Duration.ofDays(5)),
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), now));
    }

    /** Postulación de la empresa con ese técnico, todavía abierta. */
    private ServiceRequest appliedBy(UUID company, UUID technician) {
        ServiceRequest request = published();
        applicationRepository.saveAndFlush(ServiceRequestApplication.submitForCompany(request, company, technician,
                new BigDecimal("180000"), CurrencyCode.COP, Instant.now()));
        return request;
    }

    /** La empresa fue seleccionada con ese técnico; luego la solicitud se lleva al estado pedido. */
    private ServiceRequest selectedIn(String status) {
        Instant now = Instant.now();
        ServiceRequest request = published();
        ServiceRequestApplication chosen = ServiceRequestApplication.submitForCompany(request, COMPANY, TECHNICIAN,
                new BigDecimal("180000"), CurrencyCode.COP, now);
        chosen.select(now);
        applicationRepository.saveAndFlush(chosen);
        request.selectProvider(COMPANY, now);
        ServiceRequest saved = serviceRequestRepository.saveAndFlush(request);
        jdbcTemplate.update("UPDATE service_requests SET status = ? WHERE id = ?", status, saved.getId());
        return saved;
    }

    /** La empresa se postuló con ese técnico, pero el cliente eligió a otro prestador. */
    private ServiceRequest lost() {
        Instant now = Instant.now();
        ServiceRequest request = published();
        ServiceRequestApplication mine = ServiceRequestApplication.submitForCompany(request, COMPANY, TECHNICIAN,
                new BigDecimal("180000"), CurrencyCode.COP, now);
        UUID other = UUID.randomUUID();
        ServiceRequestApplication winner = ServiceRequestApplication.submit(request, other, new BigDecimal("150000"),
                CurrencyCode.COP, now);
        mine.reject(now);
        winner.select(now);
        applicationRepository.saveAllAndFlush(List.of(mine, winner));
        request.selectProvider(other, now);
        return serviceRequestRepository.saveAndFlush(request);
    }

    private ResultActions workload(String body) throws Exception {
        return mockMvc.perform(post(PATH).header("X-Internal-Api-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions workload(UUID company, UUID technician) throws Exception {
        return workload("{\"companyAdminUserId\":\"" + company + "\",\"technicianUserId\":\"" + technician + "\"}");
    }

    // ---------- Contenido ----------

    @Test
    void sinPostulacionesNoHayTrabajo() throws Exception {
        workload(COMPANY, TECHNICIAN)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.pendingServiceRequestIds", empty()))
                .andExpect(jsonPath("$.serviceRequestIds", empty()));
    }

    @Test
    void unaPostulacionAbiertaEsTrabajoPendiente() throws Exception {
        ServiceRequest request = appliedBy(COMPANY, TECHNICIAN);

        workload(COMPANY, TECHNICIAN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingServiceRequestIds", containsInAnyOrder(request.getId().toString())))
                .andExpect(jsonPath("$.serviceRequestIds", empty()));
    }

    @Test
    void unServicioSeleccionadoSinCerrarEsPendienteYUnoCerradoSoloSeReportaParaElDinero() throws Exception {
        ServiceRequest selected = selectedIn("PROVIDER_SELECTED");
        ServiceRequest contracted = selectedIn("CONTRACTED");
        ServiceRequest inProgress = selectedIn("IN_PROGRESS");
        ServiceRequest finished = selectedIn("TECHNICIAN_FINISHED");
        ServiceRequest closed = selectedIn("CLOSED");
        ServiceRequest cancelled = selectedIn("CANCELLED");

        workload(COMPANY, TECHNICIAN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingServiceRequestIds", containsInAnyOrder(selected.getId().toString(),
                        contracted.getId().toString(), inProgress.getId().toString(), finished.getId().toString())))
                .andExpect(jsonPath("$.serviceRequestIds", containsInAnyOrder(selected.getId().toString(),
                        contracted.getId().toString(), inProgress.getId().toString(), finished.getId().toString(),
                        closed.getId().toString(), cancelled.getId().toString())));
    }

    @Test
    void unaPostulacionPerdidaOUnaSolicitudQueYaNoRecibePostulacionesNoCuentan() throws Exception {
        lost();
        ServiceRequest expired = appliedBy(COMPANY, TECHNICIAN);
        jdbcTemplate.update("UPDATE service_requests SET status = 'EXPIRED' WHERE id = ?", expired.getId());

        workload(COMPANY, TECHNICIAN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingServiceRequestIds", empty()))
                .andExpect(jsonPath("$.serviceRequestIds", empty()));
    }

    @Test
    void soloCuentaElTrabajoDeEseTecnicoConEsaEmpresa() throws Exception {
        appliedBy(COMPANY, UUID.randomUUID());
        appliedBy(UUID.randomUUID(), TECHNICIAN);
        ServiceRequest mine = appliedBy(COMPANY, TECHNICIAN);

        workload(COMPANY, TECHNICIAN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingServiceRequestIds", containsInAnyOrder(mine.getId().toString())));
    }

    // ---------- Acceso y validación ----------

    @Test
    void soloConLaClaveInterna() throws Exception {
        String body = "{\"companyAdminUserId\":\"" + COMPANY + "\",\"technicianUserId\":\"" + TECHNICIAN + "\"}";
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH).header("X-Internal-Api-Key", "otra").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isUnauthorized());
    }

    @Test
    void faltaUnIdOEstaMalFormadoResponde400() throws Exception {
        workload("{\"companyAdminUserId\":\"" + COMPANY + "\"}").andExpect(status().isBadRequest());
        workload("{\"companyAdminUserId\":\"no-es-uuid\",\"technicianUserId\":\"" + TECHNICIAN + "\"}")
                .andExpect(status().isBadRequest());
    }
}
