package com.fixia.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.OutboxEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceEvidenceRepository;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import com.fixia.services.repository.ServiceRequestTransitionRepository;
import com.fixia.services.service.EventContract;
import com.fixia.services.storage.InMemoryObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvidenceControllerTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER = UUID.randomUUID();
    private static final String ADDRESS = "Calle 10 # 5-20, apto 301";

    // Válido JPEG magic bytes
    private static final byte[] VALID_JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 10, 20, 30};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ServiceRequestApplicationRepository applicationRepository;

    @Autowired
    private QuotationRepository quotationRepository;

    @Autowired
    private ServiceContractRepository contractRepository;

    @Autowired
    private ServiceEvidenceRepository evidenceRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @MockBean
    private S3Client s3Client;

    @BeforeEach
    void cleanDatabase() {
        InMemoryObjectStore.install(s3Client);
        outboxRepository.deleteAllInBatch();
        evidenceRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    /** La base H2 se comparte entre clases de prueba: las evidencias no deben quedar colgando de las solicitudes. */
    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAllInBatch();
        evidenceRepository.deleteAllInBatch();
        contractRepository.deleteAllInBatch();
        quotationRepository.deleteAllInBatch();
        applicationRepository.deleteAllInBatch();
        serviceRequestRepository.deleteAllInBatch();
    }

    private record Service(ServiceRequest request, ServiceContract contract) {
        UUID requestId() {
            return request.getId();
        }

        UUID contractId() {
            return contract.getId();
        }
    }

    private Service inProgress() throws Exception {
        Instant now = Instant.now();
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero", 4.6512, -74.0583),
                new ExactLocation(ADDRESS, 4.65, -74.08), now.plus(Duration.ofDays(5)), new BigDecimal("200000"),
                CurrencyCode.COP, List.of(), now);
        request.selectProvider(PROVIDER, now);
        ServiceRequest saved = serviceRequestRepository.saveAndFlush(request);
        Quotation quotation = quotationRepository.saveAndFlush(Quotation.send(saved, PROVIDER,
                new BigDecimal("150000.50"), CurrencyCode.COP, "Cambio de tubería", "Incluye materiales",
                now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)), 1, now));
        mockMvc.perform(post("/api/services/requests/" + saved.getId() + "/quotations/" + quotation.getId()
                + "/acceptance").with(client(CLIENT))).andExpect(status().isOk());

        ServiceContract contract = contractRepository.findByServiceRequestId(saved.getId()).orElseThrow();
        mockMvc.perform(post("/api/services/contracts/" + contract.getId() + "/start").with(professional(PROVIDER)))
                .andExpect(status().isOk());
        return new Service(saved, contract);
    }

    private static RequestPostProcessor client(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private static RequestPostProcessor professional(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id))
                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"));
    }

    @Test
    void subeEvidenciaExitosamenteEnServicioEnProgreso() throws Exception {
        Service service = inProgress();
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", VALID_JPEG);

        String responseBody = mockMvc.perform(multipart("/api/services/contracts/" + service.contractId() + "/evidences")
                        .file(file)
                        .with(professional(PROVIDER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.contractId").value(service.contractId().toString()))
                .andExpect(jsonPath("$.serviceRequestId").value(service.requestId().toString()))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.sizeBytes").value(VALID_JPEG.length))
                .andExpect(jsonPath("$.sha256").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // Verificar persistencia en base de datos
        assertThat(evidenceRepository.countByContractId(service.contractId())).isEqualTo(1);

        // Verificar evento en Outbox
        List<OutboxEvent> events = outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(service.requestId());
        assertThat(events).isNotEmpty();
        OutboxEvent lastEvent = events.get(events.size() - 1);
        assertThat(lastEvent.getEventType()).isEqualTo("evidencia.registrada");

        // Validar esquema JSON del evento
        JsonNode envelope = objectMapper.readTree(lastEvent.getPayload());
        Set<String> errors = EventContract.violations(envelope);
        assertThat(errors).isEmpty();
    }

    @Test
    void prestadorAjenoRecibe404() throws Exception {
        Service service = inProgress();
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", VALID_JPEG);

        mockMvc.perform(multipart("/api/services/contracts/" + service.contractId() + "/evidences")
                        .file(file)
                        .with(professional(OTHER_PROVIDER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void clienteYPrestadorConsultanyDescarganEvidencia() throws Exception {
        Service service = inProgress();
        MockMultipartFile file = new MockMultipartFile("file", "foto.jpg", "image/jpeg", VALID_JPEG);

        String json = mockMvc.perform(multipart("/api/services/contracts/" + service.contractId() + "/evidences")
                        .file(file)
                        .with(professional(PROVIDER)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String evidenceId = objectMapper.readTree(json).get("id").asText();

        // 1. Prestador consulta lista
        mockMvc.perform(get("/api/services/contracts/" + service.contractId() + "/evidences")
                        .with(professional(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(evidenceId));

        // 2. Cliente consulta lista
        mockMvc.perform(get("/api/services/requests/" + service.requestId() + "/evidences")
                        .with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(evidenceId));


        // 3. Prestador descarga
        mockMvc.perform(get("/api/services/contracts/" + service.contractId() + "/evidences/" + evidenceId + "/content")
                        .with(professional(PROVIDER)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + evidenceId + ".jpg\""));


        // 4. Cliente descarga
        mockMvc.perform(get("/api/services/requests/" + service.requestId() + "/evidences/" + evidenceId + "/content")
                        .with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + evidenceId + ".jpg\""));
    }

    @Test
    void rechazaArchivoConMimeIncompatible() throws Exception {
        Service service = inProgress();
        MockMultipartFile fakePdf = new MockMultipartFile("file", "fake.pdf", "application/pdf", new byte[]{1, 2, 3, 4});

        mockMvc.perform(multipart("/api/services/contracts/" + service.contractId() + "/evidences")
                        .file(fakePdf)
                        .with(professional(PROVIDER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Evidencia inválida"));
    }
}

