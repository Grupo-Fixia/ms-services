package com.fixia.services.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lectura del sobre de {@code fixia.payments.events} (GC-490). Los ejemplos de {@code src/test/resources/payments-events}
 * son copia de los de {@code ms-payments/docs/events/examples/flujo-billetera}: si ms-payments cambia el contrato, esta
 * prueba es la que avisa.
 */
class PaymentEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static String example(String name) throws IOException {
        try (InputStream in = PaymentEventTest.class.getResourceAsStream("/payments-events/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PaymentEvent parse(String json) {
        return PaymentEvent.parse(MAPPER, json);
    }

    @Test
    void leeElEjemploRealDePagoProcesado() throws IOException {
        PaymentEvent event = parse(example("02-pago.procesado.json"));

        assertThat(event.eventId()).isEqualTo(UUID.fromString("a1b2c3d4-0002-4a00-8a00-000000000002"));
        assertThat(event.eventType()).isEqualTo("pago.procesado");
        assertThat(event.sequence()).isEqualTo(2);
        assertThat(event.decidesStart()).isTrue();
        assertThat(event.dataUuid("contractId")).isEqualTo(UUID.fromString("7b8c9d0e-1f2a-4b3c-8d4e-6f708192a3b4"));
        assertThat(event.dataUuid("serviceRequestId"))
                .isEqualTo(UUID.fromString("6f1c2a3b-4d5e-4f60-8a71-92b3c4d5e6f7"));
        assertThat(event.dataUuid("clientId")).isEqualTo(PaymentEventFixtures.CLIENT);
        assertThat(event.dataUuid("providerId")).isEqualTo(PaymentEventFixtures.PROVIDER);
        PaymentEvent.Money price = event.dataMoney(event.data().path("breakdown"), "price");
        assertThat(price.amount()).isEqualByComparingTo(new BigDecimal("105000.00"));
        assertThat(price.currency()).isEqualTo("COP");
    }

    @Test
    void leeElEjemploRealDePagoFallido() throws IOException {
        PaymentEvent event = parse(example("02-alt-pago.fallido-sin-saldo.json"));

        assertThat(event.eventType()).isEqualTo("pago.fallido");
        assertThat(event.decidesStart()).isTrue();
        assertThat(event.dataUuid("contractId")).isEqualTo(UUID.fromString("7b8c9d0e-1f2a-4b3c-8d4e-6f708192a3b4"));
    }

    @Test
    void elPagoCompletadoNoDecideElInicio() throws IOException {
        PaymentEvent event = parse(example("03-pago.completado.json"));

        assertThat(event.eventType()).isEqualTo("pago.completado");
        assertThat(event.decidesStart()).isFalse();
    }

    @Test
    void ignoraLosCamposQueNoUsa() throws IOException {
        ObjectNode json = (ObjectNode) MAPPER.readTree(example("02-pago.procesado.json"));
        json.put("campoNuevoDeLaVersion1", "x");
        ((ObjectNode) json.get("data")).put("otroCampo", 1);

        assertThat(parse(json.toString()).eventType()).isEqualTo("pago.procesado");
    }

    @Test
    void rechazaMensajesQueNoCumplenElSobreSinCitarSuContenido() throws IOException {
        ObjectNode base = (ObjectNode) MAPPER.readTree(example("02-pago.procesado.json"));
        String[] invalid = {
                null, "", "   ", "esto no es json {secreto", "[]", "\"texto\"",
                without(base, "eventId"),
                with(base, "eventId", "no-es-uuid"),
                with(base, "eventType", ""),
                without(base, "eventType"),
                withSequence(base, 0),
                withSequence(base, "2"),
                with(base, "data", "[]"),
                without(base, "data"),
        };

        for (String json : invalid) {
            assertThatThrownBy(() -> parse(json)).as(String.valueOf(json))
                    .isInstanceOf(InvalidPaymentEventException.class)
                    .message().doesNotContain("secreto");
        }
    }

    @Test
    void unCampoDelCuerpoQueFaltaOEstaMalFormadoEsInvalido() throws IOException {
        PaymentEvent event = parse(example("02-pago.procesado.json"));

        assertThatThrownBy(() -> event.dataUuid("noExiste")).isInstanceOf(InvalidPaymentEventException.class);
        assertThatThrownBy(() -> event.dataUuid("status")).isInstanceOf(InvalidPaymentEventException.class);
        assertThatThrownBy(() -> event.dataMoney(event.data(), "noExiste"))
                .isInstanceOf(InvalidPaymentEventException.class);
        assertThatThrownBy(() -> event.dataMoney(event.data(), "status"))
                .isInstanceOf(InvalidPaymentEventException.class);

        ObjectNode badAmount = (ObjectNode) MAPPER.readTree(example("02-pago.procesado.json"));
        ((ObjectNode) badAmount.at("/data/breakdown/price")).put("amount", "mucho");
        PaymentEvent bad = parse(badAmount.toString());
        assertThatThrownBy(() -> bad.dataMoney(bad.data().path("breakdown"), "price"))
                .isInstanceOf(InvalidPaymentEventException.class);
    }

    private static String without(ObjectNode base, String field) {
        ObjectNode copy = base.deepCopy();
        copy.remove(field);
        return copy.toString();
    }

    /** Reemplaza un campo por texto, o por el arreglo vacío cuando el valor es {@code "[]"}. */
    private static String with(ObjectNode base, String field, String value) {
        ObjectNode copy = base.deepCopy();
        if ("[]".equals(value)) {
            copy.putArray(field);
        } else {
            copy.put(field, value);
        }
        return copy.toString();
    }

    private static String withSequence(ObjectNode base, Object sequence) {
        ObjectNode copy = base.deepCopy();
        ObjectNode aggregate = (ObjectNode) copy.get("aggregate");
        if (sequence instanceof Integer number) {
            aggregate.put("sequence", number);
        } else {
            aggregate.put("sequence", sequence.toString());
        }
        return copy.toString();
    }
}
