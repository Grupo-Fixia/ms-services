package com.fixia.services.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contratación formalizada (GC-359): copia lo acordado y solo se crea desde una cotización aceptada. */
class ServiceContractTest {

    private static final Instant NOW = Instant.parse("2026-10-22T15:00:00Z");
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final Instant AGREED = NOW.plus(Duration.ofDays(3));

    /** Solicitud con prestador seleccionado; el id lo asignaría la base al guardarla. */
    private static ServiceRequest selectedRequest() {
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga de agua en el baño",
                new ApproximateLocation("Bogotá", "Chapinero"), new ExactLocation("Calle 10 # 5-20, apto 301"),
                NOW.plus(Duration.ofDays(5)), List.of(), NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        request.selectProvider(PROVIDER, NOW.minusSeconds(60));
        return request;
    }

    private static Quotation sentQuotation(ServiceRequest request, UUID provider) {
        Quotation quotation = Quotation.send(request, provider, new BigDecimal("350000"), CurrencyCode.COP,
                "Cambio de tubería", "Incluye materiales; garantía de 3 meses", AGREED, NOW.plus(Duration.ofDays(1)),
                NOW);
        ReflectionTestUtils.setField(quotation, "id", UUID.randomUUID());
        return quotation;
    }

    /** Solicitud contratada y cotización aceptada: el estado en que se formaliza. */
    private record Deal(ServiceRequest request, Quotation quotation) {
        static Deal accepted() {
            ServiceRequest request = selectedRequest();
            Quotation quotation = sentQuotation(request, PROVIDER);
            quotation.accept(NOW.plusSeconds(30));
            request.contract(NOW.plusSeconds(30));
            return new Deal(request, quotation);
        }
    }

    @Test
    void laContratacionCopiaLoAcordadoDeLaCotizacionAceptada() {
        Deal deal = Deal.accepted();
        ServiceRequest request = deal.request();
        Quotation quotation = deal.quotation();
        Instant contractedAt = NOW.plusSeconds(30);

        ServiceContract contract = ServiceContract.formalize(request, quotation, contractedAt);

        assertThat(contract.getServiceRequestId()).isEqualTo(request.getId());
        assertThat(contract.getQuotationId()).isEqualTo(quotation.getId());
        assertThat(contract.getClientId()).isEqualTo(CLIENT);
        assertThat(contract.getProviderId()).isEqualTo(PROVIDER);
        assertThat(contract.getAmount()).isEqualTo(new BigDecimal("350000.00"));
        assertThat(contract.getCurrency()).isEqualTo(CurrencyCode.COP);
        assertThat(contract.getScope()).isEqualTo("Cambio de tubería");
        assertThat(contract.getConditions()).isEqualTo("Incluye materiales; garantía de 3 meses");
        assertThat(contract.getAgreedAt()).isEqualTo(AGREED);
        assertThat(contract.getContractedAt()).isEqualTo(contractedAt);
    }

    @Test
    void soloSeFormalizaConUnaCotizacionAceptada() {
        ServiceRequest request = selectedRequest();
        Quotation sent = sentQuotation(request, PROVIDER);
        request.contract(NOW.plusSeconds(30));

        assertThatThrownBy(() -> ServiceContract.formalize(request, sent, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Solo se formaliza la contratación con una cotización aceptada");
    }

    @Test
    void soloSeFormalizaSiLaSolicitudYaEstaContratada() {
        ServiceRequest request = selectedRequest();
        Quotation quotation = sentQuotation(request, PROVIDER);
        quotation.accept(NOW.plusSeconds(30));

        assertThatThrownBy(() -> ServiceContract.formalize(request, quotation, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Solo se formaliza la contratación de una solicitud contratada");
    }

    @Test
    void laCotizacionDebeSerDeEsaSolicitudYDelPrestadorSeleccionado() {
        Deal deal = Deal.accepted();
        ServiceRequest request = deal.request();
        Quotation quotation = deal.quotation();
        Quotation ofOther = Deal.accepted().quotation();

        assertThatThrownBy(() -> ServiceContract.formalize(request, ofOther, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La cotización no es de esta solicitud");

        ReflectionTestUtils.setField(request, "selectedProviderId", UUID.randomUUID());
        assertThatThrownBy(() -> ServiceContract.formalize(request, quotation, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La cotización no es del prestador seleccionado");
    }

    @Test
    void faltandoUnDatoSeRechaza() {
        ServiceRequest request = Deal.accepted().request();
        Quotation quotation = Deal.accepted().quotation();

        assertThatThrownBy(() -> ServiceContract.formalize(null, quotation, NOW))
                .hasMessage("La solicitud es obligatoria");
        assertThatThrownBy(() -> ServiceContract.formalize(request, null, NOW))
                .hasMessage("La cotización es obligatoria");
        assertThatThrownBy(() -> ServiceContract.formalize(request, quotation, null))
                .hasMessage("El momento de la contratación es obligatorio");
    }

    @Test
    void laIdentidadSeBasaEnElIdYNoExponeDatosDelAcuerdoEnElTexto() {
        Deal deal = Deal.accepted();
        ServiceRequest request = deal.request();
        Quotation quotation = deal.quotation();
        ServiceContract first = ServiceContract.formalize(request, quotation, NOW);
        ServiceContract second = ServiceContract.formalize(request, quotation, NOW);
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(first, "id", id);
        ReflectionTestUtils.setField(second, "id", id);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(null);
        assertThat(first.toString()).contains(id.toString()).doesNotContain("350000").doesNotContain("tubería");
    }
}
