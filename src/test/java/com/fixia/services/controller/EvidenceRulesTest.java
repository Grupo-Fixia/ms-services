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
import com.fixia.services.service.EventContract;
import com.fixia.services.storage.InMemoryObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reglas de las evidencias (GC-439): permisos por participante, estados del servicio, límites, archivos inválidos,
 * duplicidad, fallos parciales entre el almacenamiento y la base, caída del almacenamiento e integridad del contenido.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvidenceRulesTest {

    /**
     * Fecha acordada distinta para cada contrato de la prueba (3 horas después de la anterior): un mismo prestador no
     * puede quedar contratado dos veces a la misma hora ({@code ProviderScheduleGuard}).
     */
    private static final java.util.concurrent.atomic.AtomicInteger SLOT = new java.util.concurrent.atomic.AtomicInteger();

    private static Instant nextFreeSlot(Instant now) {
        return now.plus(Duration.ofDays(3)).plus(Duration.ofHours(3L * SLOT.incrementAndGet()));
    }

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();
    private static final String EVENT = "evidencia.registrada";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;
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
    @SpyBean
    private OutboxEventRepository outboxRepository;

    @MockBean
    private S3Client s3Client;

    private InMemoryObjectStore store;

    @BeforeEach
    void setUp() {
        Mockito.reset(outboxRepository);
        store = InMemoryObjectStore.install(s3Client);
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

    // ---------------------------------------------------------------------------------------------------------------
    // Escenarios del servicio
    // ---------------------------------------------------------------------------------------------------------------

    private record Service(UUID requestId, UUID contractId) {
    }

    /** Contratado: aceptada la cotización, todavía sin iniciar. */
    private Service contracted() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero", 4.6512, -74.0583),
                new ExactLocation("Calle 10 # 5-20, apto 301", 4.65, -74.08), now.plus(Duration.ofDays(5)),
                new BigDecimal("200000"), CurrencyCode.COP, List.of(), now);
        request.selectProvider(PROVIDER, now);
        ServiceRequest saved = serviceRequestRepository.saveAndFlush(request);
        Quotation quotation = quotationRepository.saveAndFlush(Quotation.send(saved, PROVIDER,
                new BigDecimal("150000.50"), CurrencyCode.COP, "Cambio de tubería", "Incluye materiales",
                nextFreeSlot(now), now.plus(Duration.ofDays(1)), 1, now));
        mockMvc.perform(post("/api/services/requests/" + saved.getId() + "/quotations/" + quotation.getId()
                + "/acceptance").with(client(CLIENT))).andExpect(status().isOk());
        ServiceContract contract = contractRepository.findByServiceRequestId(saved.getId()).orElseThrow();
        return new Service(saved.getId(), contract.getId());
    }

    private Service inProgress() throws Exception {
        Service service = contracted();
        ContractPayments.hold(jdbcTemplate, service.contractId());
        mockMvc.perform(post("/api/services/contracts/" + service.contractId() + "/start")
                .with(professional(PROVIDER))).andExpect(status().isOk());
        return service;
    }

    private Service technicianFinished() throws Exception {
        Service service = inProgress();
        mockMvc.perform(post("/api/services/contracts/" + service.contractId() + "/finish")
                .with(professional(PROVIDER))).andExpect(status().isOk());
        return service;
    }

    private Service closed() throws Exception {
        Service service = technicianFinished();
        mockMvc.perform(post("/api/services/requests/" + service.requestId() + "/closure").with(client(CLIENT)))
                .andExpect(status().isOk());
        return service;
    }

    private static RequestPostProcessor client(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_CLIENT"));
    }

    private static RequestPostProcessor professional(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id))
                .authorities(new SimpleGrantedAuthority("ROLE_PROFESSIONAL"));
    }

    private static RequestPostProcessor company(UUID id) {
        return jwt().jwt(token -> token.subject("usr_" + id)).authorities(new SimpleGrantedAuthority("ROLE_COMPANY"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Archivos
    // ---------------------------------------------------------------------------------------------------------------

    /** JPEG mínimo válido por sus bytes iniciales; {@code seed} lo hace distinto de otro para no ser un duplicado. */
    private static byte[] jpeg(int seed) {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, (byte) seed, (byte) (seed >> 8)};
    }

    private static byte[] png(int seed) {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, (byte) seed};
    }

    private static byte[] webp(int seed) {
        return new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', (byte) seed};
    }

    private static byte[] pdf(int seed) {
        return new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7', (byte) seed};
    }

    private org.springframework.test.web.servlet.ResultActions upload(Service service, UUID provider, String type,
                                                                       byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/services/contracts/" + service.contractId() + "/evidences")
                .file(new MockMultipartFile("file", "archivo", type, bytes))
                .with(professional(provider)));
    }

    private String uploadOk(Service service, byte[] bytes) throws Exception {
        String json = upload(service, PROVIDER, "image/jpeg", bytes).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private String contractPath(Service service) {
        return "/api/services/contracts/" + service.contractId() + "/evidences";
    }

    private String requestPath(Service service) {
        return "/api/services/requests/" + service.requestId() + "/evidences";
    }

    private List<OutboxEvent> evidenceEvents(Service service) {
        return outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(service.requestId()).stream()
                .filter(event -> EVENT.equals(event.getEventType())).toList();
    }

    private void assertNothingStored(Service service) {
        assertThat(evidenceRepository.countByContractId(service.contractId())).isZero();
        assertThat(evidenceEvents(service)).isEmpty();
        assertThat(store.objects()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Permisos
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void soloLosParticipantesDelServicioVenLasEvidencias() throws Exception {
        Service service = inProgress();
        String id = uploadOk(service, jpeg(1));

        mockMvc.perform(get(contractPath(service)).with(professional(OTHER_PROVIDER))).andExpect(status().isNotFound());
        mockMvc.perform(get(contractPath(service) + "/" + id + "/content").with(professional(OTHER_PROVIDER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(requestPath(service)).with(client(OTHER_CLIENT))).andExpect(status().isNotFound());
        mockMvc.perform(get(requestPath(service) + "/" + id + "/content").with(client(OTHER_CLIENT)))
                .andExpect(status().isNotFound());
    }

    @Test
    void elClienteNoSubeNiUsaLaRutaDelContratoYElTecnicoNoUsaLaDelCliente() throws Exception {
        Service service = inProgress();
        String id = uploadOk(service, jpeg(1));

        mockMvc.perform(multipart(contractPath(service)).file(new MockMultipartFile("file", "a.jpg", "image/jpeg",
                jpeg(2))).with(client(CLIENT))).andExpect(status().isForbidden());
        mockMvc.perform(get(contractPath(service)).with(client(CLIENT))).andExpect(status().isForbidden());
        // La descarga por contrato admite ambos roles en seguridad, pero solo el técnico contratado la resuelve.
        mockMvc.perform(get(contractPath(service) + "/" + id + "/content").with(client(CLIENT)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(requestPath(service)).with(professional(PROVIDER))).andExpect(status().isForbidden());
        assertThat(evidenceRepository.countByContractId(service.contractId())).isEqualTo(1);
    }

    /**
     * GC-551: una empresa consulta las evidencias de los contratos en que es la prestadora, pero nunca sube, y las de un
     * contrato ajeno no existen para ella (404, igual que para un técnico ajeno).
     */
    @Test
    void unaEmpresaAjenaNoVeLasEvidenciasYNingunaEmpresaSube() throws Exception {
        Service service = inProgress();
        String id = uploadOk(service, jpeg(1));

        mockMvc.perform(multipart(contractPath(service)).file(new MockMultipartFile("file", "a.jpg", "image/jpeg",
                jpeg(2))).with(company(COMPANY))).andExpect(status().isForbidden());
        mockMvc.perform(get(contractPath(service)).with(company(COMPANY))).andExpect(status().isNotFound());
        mockMvc.perform(get(contractPath(service) + "/" + id + "/content").with(company(COMPANY)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(requestPath(service)).with(company(COMPANY))).andExpect(status().isForbidden());
        mockMvc.perform(get(requestPath(service) + "/" + id + "/content").with(company(COMPANY)))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinSesionTodoRespondeNoAutenticado() throws Exception {
        Service service = inProgress();
        String id = uploadOk(service, jpeg(1));

        mockMvc.perform(multipart(contractPath(service)).file(new MockMultipartFile("file", "a.jpg", "image/jpeg",
                jpeg(2)))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(contractPath(service))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(contractPath(service) + "/" + id + "/content")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(requestPath(service))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(requestPath(service) + "/" + id + "/content")).andExpect(status().isUnauthorized());
    }

    @Test
    void unaEvidenciaNoSeDescargaDesdeOtroServicioNiConUnIdInexistente() throws Exception {
        Service first = inProgress();
        Service second = inProgress();
        String id = uploadOk(first, jpeg(1));

        mockMvc.perform(get(contractPath(second) + "/" + id + "/content").with(professional(PROVIDER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(requestPath(second) + "/" + id + "/content").with(client(CLIENT)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(contractPath(first) + "/" + UUID.randomUUID() + "/content").with(professional(PROVIDER)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(contractPath(first) + "/no-es-un-uuid/content").with(professional(PROVIDER)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Estados y límites
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void sePuedeSubirEnCursoYTrasLaFinalizacionDelTecnico() throws Exception {
        uploadOk(inProgress(), jpeg(1));
        uploadOk(technicianFinished(), jpeg(1));
    }

    @Test
    void noSeSubeAntesDeIniciarNiDespuesDelCierre() throws Exception {
        Service contracted = contracted();
        upload(contracted, PROVIDER, "image/jpeg", jpeg(1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Evidencia no permitida"));
        assertNothingStored(contracted);

        Service closed = closed();
        upload(closed, PROVIDER, "image/jpeg", jpeg(1)).andExpect(status().isConflict());
        assertNothingStored(closed);
    }

    @Test
    void conDiezEvidenciasLaOnceavaSeRechazaSinTocarElAlmacenamiento() throws Exception {
        Service service = inProgress();
        for (int i = 1; i <= 10; i++) {
            uploadOk(service, jpeg(i));
        }
        upload(service, PROVIDER, "image/jpeg", jpeg(11)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Límite de evidencias alcanzado"));

        assertThat(evidenceRepository.countByContractId(service.contractId())).isEqualTo(10);
        assertThat(store.puts()).isEqualTo(10);
        assertThat(evidenceEvents(service)).hasSize(10);
    }

    @Test
    void aceptaLosCuatroFormatosPermitidos() throws Exception {
        Service service = inProgress();
        upload(service, PROVIDER, "image/jpeg", jpeg(1)).andExpect(status().isCreated());
        upload(service, PROVIDER, "image/png", png(1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"));
        upload(service, PROVIDER, "image/webp", webp(1)).andExpect(status().isCreated());
        upload(service, PROVIDER, "application/pdf", pdf(1)).andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Archivos inválidos
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void rechazaArchivosInvalidosSinDejarNadaAtras() throws Exception {
        Service service = inProgress();

        upload(service, PROVIDER, "image/jpeg", new byte[0]).andExpect(status().isBadRequest());
        upload(service, PROVIDER, "text/plain", "hola".getBytes()).andExpect(status().isBadRequest());
        upload(service, PROVIDER, "application/x-msdownload", jpeg(1)).andExpect(status().isBadRequest());
        upload(service, PROVIDER, "image/png", jpeg(1)).andExpect(status().isBadRequest());
        upload(service, PROVIDER, "application/pdf", new byte[]{1, 2, 3, 4, 5}).andExpect(status().isBadRequest());
        upload(service, PROVIDER, "", jpeg(1)).andExpect(status().isBadRequest());
        mockMvc.perform(multipart(contractPath(service)).with(professional(PROVIDER)))
                .andExpect(status().isBadRequest());

        assertNothingStored(service);
    }

    @Test
    void rechazaUnArchivoDeMasDeDiezMegabytes() throws Exception {
        Service service = inProgress();
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(jpeg(1), 0, big, 0, 8);

        upload(service, PROVIDER, "image/jpeg", big).andExpect(status().isBadRequest());
        assertNothingStored(service);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Duplicidad
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void subirElMismoArchivoDosVecesDevuelveLaMismaEvidenciaSinDuplicar() throws Exception {
        Service service = inProgress();
        String first = uploadOk(service, jpeg(7));

        upload(service, PROVIDER, "image/jpeg", jpeg(7)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first));

        assertThat(evidenceRepository.countByContractId(service.contractId())).isEqualTo(1);
        assertThat(store.puts()).isEqualTo(1);
        assertThat(evidenceEvents(service)).hasSize(1);
    }

    @Test
    void elDuplicadoSeReconoceAunConElLimiteLleno() throws Exception {
        Service service = inProgress();
        for (int i = 1; i <= 10; i++) {
            uploadOk(service, jpeg(i));
        }
        upload(service, PROVIDER, "image/jpeg", jpeg(3)).andExpect(status().isOk());
        assertThat(evidenceRepository.countByContractId(service.contractId())).isEqualTo(10);
    }

    @Test
    void elMismoArchivoEnOtroServicioEsUnaEvidenciaDistinta() throws Exception {
        Service first = inProgress();
        Service second = inProgress();
        String a = uploadOk(first, jpeg(7));
        String b = uploadOk(second, jpeg(7));
        assertThat(a).isNotEqualTo(b);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fallos parciales y almacenamiento caído
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void conElAlmacenamientoCaidoNoQuedaEvidenciaNiEventoYSeRecupera() throws Exception {
        Service service = inProgress();
        store.goDown();

        upload(service, PROVIDER, "image/jpeg", jpeg(1)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Almacenamiento no disponible"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Connection"))));
        assertThat(evidenceRepository.countByContractId(service.contractId())).isZero();
        assertThat(evidenceEvents(service)).isEmpty();

        store.comeBack();
        upload(service, PROVIDER, "image/jpeg", jpeg(1)).andExpect(status().isCreated());
        assertThat(evidenceEvents(service)).hasSize(1);
    }

    @Test
    void siElAlmacenamientoNoConfirmaLaCargaNoHayEvidenciaYElObjetoSeBorra() throws Exception {
        Service service = inProgress();
        store.answerWithWrongEtag();

        upload(service, PROVIDER, "image/jpeg", jpeg(1)).andExpect(status().isServiceUnavailable());

        assertThat(store.puts()).isEqualTo(1);
        assertThat(store.deleted()).hasSize(1);
        assertNothingStored(service);
    }

    @Test
    void siFallaElRegistroDelEventoDespuesDeSubirElObjetoSeBorra() throws Exception {
        Service service = inProgress();
        Mockito.doThrow(new IllegalStateException("fallo simulado del Outbox")).when(outboxRepository)
                .save(Mockito.argThat((OutboxEvent event) -> event != null && EVENT.equals(event.getEventType())));

        assertThatThrownBy(() -> upload(service, PROVIDER, "image/jpeg", jpeg(1)))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(store.puts()).isEqualTo(1);
        assertThat(store.deleted()).hasSize(1);
        assertNothingStored(service);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Descarga e integridad
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void ambosParticipantesDescarganExactamenteLosBytesSubidos() throws Exception {
        Service service = inProgress();
        byte[] original = png(9);
        String json = upload(service, PROVIDER, "image/png", original).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(json).get("id").asText();

        for (var request : List.of(
                get(contractPath(service) + "/" + id + "/content").with(professional(PROVIDER)),
                get(requestPath(service) + "/" + id + "/content").with(client(CLIENT)))) {
            byte[] downloaded = mockMvc.perform(request).andExpect(status().isOk())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                    .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, original.length))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(downloaded).isEqualTo(original);
        }
    }

    @Test
    void siElObjetoAlmacenadoFueAlteradoNoSeEntregaElArchivo() throws Exception {
        Service service = inProgress();
        String id = uploadOk(service, jpeg(1));
        String key = store.objects().keySet().iterator().next();
        byte[] altered = jpeg(1);
        altered[7] = 99;
        store.tamper(key, altered);

        for (var request : List.of(
                get(contractPath(service) + "/" + id + "/content").with(professional(PROVIDER)),
                get(requestPath(service) + "/" + id + "/content").with(client(CLIENT)))) {
            mockMvc.perform(request).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.title").value("Integridad de la evidencia"))
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
        }
    }

    @Test
    void siElObjetoYaNoExisteLaDescargaRespondeNoEncontrada() throws Exception {
        Service service = inProgress();
        String id = uploadOk(service, jpeg(1));
        store.remove(store.objects().keySet().iterator().next());

        mockMvc.perform(get(contractPath(service) + "/" + id + "/content").with(professional(PROVIDER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void conElAlmacenamientoCaidoLaDescargaRespondeNoDisponibleYLaListaSigueFuncionando() throws Exception {
        Service service = inProgress();
        String id = uploadOk(service, jpeg(1));
        store.goDown();

        mockMvc.perform(get(contractPath(service) + "/" + id + "/content").with(professional(PROVIDER)))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get(requestPath(service)).with(client(CLIENT))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Evento
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cadaSubidaNuevaPublicaUnSoloEventoValidoYConsecutivo() throws Exception {
        Service service = inProgress();
        long before = outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(service.requestId()).size();
        uploadOk(service, jpeg(1));
        uploadOk(service, jpeg(2));
        upload(service, PROVIDER, "image/jpeg", jpeg(2)).andExpect(status().isOk());

        List<OutboxEvent> all = outboxRepository.findByAggregateIdOrderByAggregateSequenceAsc(service.requestId());
        assertThat(all).hasSize((int) before + 2);
        assertThat(evidenceEvents(service)).hasSize(2);
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).getAggregateSequence()).isEqualTo(all.get(i - 1).getAggregateSequence() + 1);
        }
        for (OutboxEvent event : evidenceEvents(service)) {
            JsonNode envelope = objectMapper.readTree(event.getPayload());
            assertThat(EventContract.violations(envelope)).isEmpty();
            assertThat(envelope.at("/data/uploadedBy").asText()).isEqualTo(PROVIDER.toString());
            assertThat(envelope.at("/data/sha256").asText()).hasSize(64);
        }
    }
}
