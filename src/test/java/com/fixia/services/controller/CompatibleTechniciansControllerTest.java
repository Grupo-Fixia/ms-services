package com.fixia.services.controller;

import com.fixia.services.client.MatchingClient;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.exception.InvalidMatchingQueryException;
import com.fixia.services.exception.MatchingLookupException;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Técnicos compatibles con una solicitud propia (GC-474). ms-matching-geo se simula con {@code @MockBean}: el cliente
 * HTTP se prueba en {@code MatchingClientTest}. La autenticación se simula con el token ya validado ({@code jwt()}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompatibleTechniciansControllerTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID OTHER_CLIENT = UUID.randomUUID();
    private static final UUID TECHNICIAN = UUID.randomUUID();
    private static final String EXACT_ADDRESS = "Calle 10 # 5-20, apto 301";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceRequestRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private MatchingClient matchingClient;

    @BeforeEach
    void setUp() {
        repository.deleteAllInBatch();
        when(matchingClient.findCompatible(any(), anyInt(), any())).thenReturn(page("c1"));
    }

    // ---------- Utilidades ----------

    private static MatchingClient.CompatibleTechnicians page(String nextCursor) {
        return new MatchingClient.CompatibleTechnicians(List.of(
                new MatchingClient.CompatibleTechnician(TECHNICIAN, "Carlos G.", List.of("PLUMBING"), "Bogotá")),
                nextCursor);
    }

    private ServiceRequest request(UUID clientId) {
        Instant now = Instant.now();
        return repository.saveAndFlush(ServiceRequest.publish(clientId, ServiceCategory.PLUMBING,
                "Fuga de agua en el baño", new ApproximateLocation("Bogotá", "Chapinero", 4.6533, -74.0836),
                new ExactLocation(EXACT_ADDRESS), now.plus(Duration.ofDays(2)), new BigDecimal("200000"),
                CurrencyCode.COP, List.of(), now));
    }

    private static String url(ServiceRequest request) {
        return "/api/services/requests/" + request.getId() + "/compatible-technicians";
    }

    private static RequestPostProcessor role(UUID userId, String role) {
        return jwt().jwt(token -> token.subject("usr_" + userId)).authorities(new SimpleGrantedAuthority(role));
    }

    private static RequestPostProcessor client(UUID userId) {
        return role(userId, "ROLE_CLIENT");
    }

    // ---------- Consulta ----------

    @Test
    void elClienteDuenoRecibeLosTecnicosCompatiblesDeSuSolicitud() throws Exception {
        ServiceRequest request = request(CLIENT);

        mockMvc.perform(get(url(request)).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(TECHNICIAN.toString()))
                .andExpect(jsonPath("$.items[0].displayName").value("Carlos G."))
                .andExpect(jsonPath("$.items[0].categories[0]").value("PLUMBING"))
                .andExpect(jsonPath("$.items[0].city").value("Bogotá"))
                .andExpect(jsonPath("$.nextCursor").value("c1"));
    }

    @Test
    void aMatchingSoloLeLlegaLoAproximadoDeLaSolicitudYLoQueElClientePidio() throws Exception {
        ServiceRequest request = request(CLIENT);

        mockMvc.perform(get(url(request) + "?limit=7&cursor=c0").with(client(CLIENT))).andExpect(status().isOk());

        ArgumentCaptor<ServiceRequest> sent = ArgumentCaptor.forClass(ServiceRequest.class);
        verify(matchingClient).findCompatible(sent.capture(), eq(7), eq("c0"));
        ServiceRequest forwarded = sent.getValue();
        assertThat(forwarded.getId()).isEqualTo(request.getId());
        assertThat(forwarded.getCategory()).isEqualTo(ServiceCategory.PLUMBING);
        assertThat(forwarded.getApproximateLocation().city()).isEqualTo("Bogotá");
        assertThat(forwarded.getApproximateLocation().latitude()).isEqualTo(4.65);
        assertThat(forwarded.getExpectedAt()).isEqualTo(request.getExpectedAt());
    }

    @Test
    void porDefectoPideVeintePorPaginaYSinCursor() throws Exception {
        ServiceRequest request = request(CLIENT);

        mockMvc.perform(get(url(request)).with(client(CLIENT))).andExpect(status().isOk());

        verify(matchingClient).findCompatible(any(), eq(20), isNull());
    }

    @Test
    void laRespuestaNoRevelaLaDireccionExactaNiAlClienteNiLaZonaDelTecnico() throws Exception {
        ServiceRequest request = request(CLIENT);

        mockMvc.perform(get(url(request)).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Calle 10"))))
                .andExpect(content().string(not(containsString(CLIENT.toString()))))
                .andExpect(content().string(not(containsString("circle"))))
                .andExpect(content().string(not(containsString("radius"))))
                .andExpect(content().string(not(containsString("latitude"))));
    }

    @Test
    void unaPaginaVaciaConCursorSeDevuelveTalCual() throws Exception {
        when(matchingClient.findCompatible(any(), anyInt(), any()))
                .thenReturn(new MatchingClient.CompatibleTechnicians(List.of(), "c7"));

        mockMvc.perform(get(url(request(CLIENT))).with(client(CLIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value("c7"));
    }

    // ---------- Quién puede consultar ----------

    @Test
    void laSolicitudDeOtroClienteLaInexistenteOUnIdInvalidoDanElMismo404SinLlamarAMatching() throws Exception {
        ServiceRequest request = request(OTHER_CLIENT);

        mockMvc.perform(get(url(request)).with(client(CLIENT))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/services/requests/" + UUID.randomUUID() + "/compatible-technicians")
                .with(client(CLIENT))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/services/requests/no-es-un-uuid/compatible-technicians").with(client(CLIENT)))
                .andExpect(status().isNotFound());

        verify(matchingClient, never()).findCompatible(any(), anyInt(), any());
    }

    @Test
    void soloUnClientePuedeConsultar() throws Exception {
        ServiceRequest request = request(CLIENT);

        mockMvc.perform(get(url(request)).with(role(CLIENT, "ROLE_PROFESSIONAL"))).andExpect(status().isForbidden());
        mockMvc.perform(get(url(request)).with(role(CLIENT, "ROLE_COMPANY"))).andExpect(status().isForbidden());
        mockMvc.perform(get(url(request))).andExpect(status().isUnauthorized());

        verify(matchingClient, never()).findCompatible(any(), anyInt(), any());
    }

    // ---------- Solicitud abierta ----------

    @Test
    void unaSolicitudQueYaNoRecibePostulacionesDa409SinLlamarAMatching() throws Exception {
        ServiceRequest cancelled = request(CLIENT);
        jdbcTemplate.update("UPDATE service_requests SET status = 'CANCELLED' WHERE id = ?", cancelled.getId());
        ServiceRequest selected = request(CLIENT);
        jdbcTemplate.update("UPDATE service_requests SET status = 'PROVIDER_SELECTED', selected_provider_id = ? "
                + "WHERE id = ?", UUID.randomUUID(), selected.getId());
        ServiceRequest overdue = request(CLIENT);
        jdbcTemplate.update("UPDATE service_requests SET expected_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))), overdue.getId());

        for (ServiceRequest closed : List.of(cancelled, selected, overdue)) {
            mockMvc.perform(get(url(closed)).with(client(CLIENT)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Solicitud no disponible"));
        }

        verify(matchingClient, never()).findCompatible(any(), anyInt(), any());
    }

    // ---------- Validación y fallos de matching ----------

    @Test
    void unLimiteFueraDeRangoOQueNoEsNumeroDa400SinLlamarAMatching() throws Exception {
        ServiceRequest request = request(CLIENT);

        for (String limit : List.of("0", "51", "-1", "abc", "2.5")) {
            mockMvc.perform(get(url(request) + "?limit=" + limit).with(client(CLIENT)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Consulta inválida"));
        }
        mockMvc.perform(get(url(request) + "?limit=1").with(client(CLIENT))).andExpect(status().isOk());
        mockMvc.perform(get(url(request) + "?limit=50").with(client(CLIENT))).andExpect(status().isOk());
    }

    @Test
    void unCursorQueMatchingRechazaDa400() throws Exception {
        when(matchingClient.findCompatible(any(), anyInt(), any()))
                .thenThrow(InvalidMatchingQueryException.invalidCursor());

        mockMvc.perform(get(url(request(CLIENT)) + "?cursor=falso").with(client(CLIENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El cursor no es válido"));
    }

    @Test
    void siMatchingFallaResponde503YNuncaUnaListaVacia() throws Exception {
        when(matchingClient.findCompatible(any(), anyInt(), any()))
                .thenThrow(new MatchingLookupException("ms-matching-geo no respondió"));

        mockMvc.perform(get(url(request(CLIENT))).with(client(CLIENT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Técnicos compatibles no disponibles"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }
}
