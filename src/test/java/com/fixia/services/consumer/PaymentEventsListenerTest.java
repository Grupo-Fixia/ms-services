package com.fixia.services.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El listener confirma el offset solo después de guardar el efecto (GC-490), salta lo que no tiene arreglo y deja subir
 * los fallos de infraestructura para que el mensaje se reintente.
 */
class PaymentEventsListenerTest {

    private static final String VALID = """
            {"eventId":"a1b2c3d4-0002-4a00-8a00-000000000002","eventType":"pago.procesado","schemaVersion":1,
             "aggregate":{"type":"Payment","id":"3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f","sequence":2},"data":{}}
            """;

    private final PaymentEventHandler handler = mock(PaymentEventHandler.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final PaymentEventsListener listener = new PaymentEventsListener(handler, new ObjectMapper());

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("fixia.payments.events", 0, 7L, UUID.randomUUID().toString(), value);
    }

    @Test
    void unMensajeValidoSeAplicaYDespuesSeConfirma() {
        when(handler.handle(any())).thenReturn(PaymentEventHandler.Outcome.APPLIED);

        listener.onMessage(record(VALID), ack);

        verify(handler).handle(any(PaymentEvent.class));
        verify(ack).acknowledge();
    }

    @Test
    void unMensajeInvalidoSeSaltaYSeConfirmaSinAplicarlo() {
        listener.onMessage(record("{secreto-que-no-debe-registrarse"), ack);
        listener.onMessage(record(null), ack);

        verify(handler, never()).handle(any());
        verify(ack, org.mockito.Mockito.times(2)).acknowledge();
    }

    @Test
    void unMensajeConDatosDelContratoQueFaltanTambienSeSalta() {
        when(handler.handle(any())).thenThrow(new InvalidPaymentEventException("Falta data.contractId o no es válido"));

        listener.onMessage(record(VALID), ack);

        verify(ack).acknowledge();
    }

    @Test
    void siLaBaseFallaNoSeConfirmaYElMensajeSeReintenta() {
        when(handler.handle(any())).thenThrow(new IllegalStateException("base caída"));

        assertThatThrownBy(() -> listener.onMessage(record(VALID), ack))
                .isInstanceOf(IllegalStateException.class);

        verify(ack, never()).acknowledge();
    }
}
