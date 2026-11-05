package com.fixia.services.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fixia.services.consumer.PaymentEventHandler.Outcome;
import com.fixia.services.domain.ContractPayment;
import com.fixia.services.domain.ContractPaymentStatus;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.repository.ContractPaymentRepository;
import com.fixia.services.repository.ProcessedEventRepository;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reglas del consumidor de pagos (GC-490) contra las migraciones reales: lo que ms-payments confirmó queda reflejado en
 * la contratación, las repeticiones y los hechos tardíos no cambian nada y un evento que no cuadra con la contratación
 * nunca autoriza el inicio.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentEventHandlerTest {

    @Autowired
    private PaymentEventHandler handler;

    @Autowired
    private ContractPaymentRepository payments;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private ServiceRequestRepository requests;

    @Autowired
    private QuotationRepository quotations;

    @Autowired
    private ServiceContractRepository contracts;

    @Autowired
    private ObjectMapper mapper;

    private ServiceContract contract;

    @BeforeEach
    void cleanDatabase() {
        processedEvents.deleteAllInBatch();
        // El borrado en cascada se lleva cotizaciones, contrataciones y pagos.
        requests.deleteAllInBatch();
        contract = PaymentEventFixtures.contract(requests, quotations, contracts);
    }

    private Outcome handle(ObjectNode event) {
        return handler.handle(PaymentEvent.parse(mapper, event.toString()));
    }

    private ContractPaymentStatus statusOfPayment() {
        return payments.findById(contract.getId()).map(ContractPayment::getStatus).orElse(ContractPaymentStatus.PENDING);
    }

    @Test
    void sinNingunHechoDePagoLaContratacionEstaPendiente() {
        assertThat(statusOfPayment()).isEqualTo(ContractPaymentStatus.PENDING);
        assertThat(payments.count()).isZero();
    }

    @Test
    void pagoProcesadoDejaElSaldoRetenido() {
        ObjectNode event = PaymentEventFixtures.processed(contract, 2);

        assertThat(handle(event)).isEqualTo(Outcome.APPLIED);

        ContractPayment payment = payments.findById(contract.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(ContractPaymentStatus.HELD);
        assertThat(payment.getServiceRequestId()).isEqualTo(contract.getServiceRequestId());
        assertThat(payment.getLastEventId()).isEqualTo(PaymentEventFixtures.eventId(event));
        assertThat(payment.getLastSequence()).isEqualTo(2);
        assertThat(processedEvents.existsById(PaymentEventFixtures.eventId(event))).isTrue();
    }

    @Test
    void pagoFallidoDejaElPagoFallidoSinGuardarElMotivo() {
        assertThat(handle(PaymentEventFixtures.failed(contract, 2))).isEqualTo(Outcome.APPLIED);

        assertThat(statusOfPayment()).isEqualTo(ContractPaymentStatus.FAILED);
    }

    @Test
    void elMismoEventoDosVecesSeAplicaUnaSolaVez() {
        ObjectNode event = PaymentEventFixtures.processed(contract, 2);

        assertThat(handle(event)).isEqualTo(Outcome.APPLIED);
        ContractPayment first = payments.findById(contract.getId()).orElseThrow();
        assertThat(handle(event)).isEqualTo(Outcome.DUPLICATE);
        assertThat(handle(event)).isEqualTo(Outcome.DUPLICATE);

        assertThat(payments.count()).isEqualTo(1);
        assertThat(processedEvents.count()).isEqualTo(1);
        assertThat(payments.findById(contract.getId()).orElseThrow().getUpdatedAt()).isEqualTo(first.getUpdatedAt());
    }

    @Test
    void trasUnFalloElClienteRecargaYElReintentoDejaElSaldoRetenido() {
        handle(PaymentEventFixtures.failed(contract, 2));
        assertThat(statusOfPayment()).isEqualTo(ContractPaymentStatus.FAILED);

        assertThat(handle(PaymentEventFixtures.processed(contract, 3))).isEqualTo(Outcome.APPLIED);

        assertThat(statusOfPayment()).isEqualTo(ContractPaymentStatus.HELD);
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void unFallidoTardioNuncaDeshaceUnaRetencionConfirmada() {
        handle(PaymentEventFixtures.processed(contract, 3));

        // Llega después, pero es un hecho más viejo de la historia del pago (secuencia 2).
        assertThat(handle(PaymentEventFixtures.failed(contract, 2))).isEqualTo(Outcome.STALE);

        assertThat(statusOfPayment()).isEqualTo(ContractPaymentStatus.HELD);
        assertThat(payments.findById(contract.getId()).orElseThrow().getLastSequence()).isEqualTo(3);
    }

    @Test
    void unaSecuenciaRepetidaConOtroEventIdTampocoCambiaNada() {
        handle(PaymentEventFixtures.failed(contract, 2));

        assertThat(handle(PaymentEventFixtures.processed(contract, 2))).isEqualTo(Outcome.STALE);

        assertThat(statusOfPayment()).isEqualTo(ContractPaymentStatus.FAILED);
    }

    @Test
    void losDemasHechosDePagoSeRegistranPeroNoDecidenElInicio() {
        ObjectNode completed = PaymentEventFixtures.completed(contract, 3);

        assertThat(handle(completed)).isEqualTo(Outcome.NOT_RELEVANT);

        assertThat(payments.count()).isZero();
        assertThat(processedEvents.existsById(PaymentEventFixtures.eventId(completed))).isTrue();
    }

    @Test
    void unaContratacionInexistenteNoAutorizaNadaNiSeReintenta() {
        ObjectNode event = PaymentEventFixtures.processed(contract, 2);
        ((ObjectNode) event.get("data")).put("contractId", UUID.randomUUID().toString());

        assertThat(handle(event)).isEqualTo(Outcome.REJECTED);

        assertThat(payments.count()).isZero();
        assertThat(processedEvents.existsById(PaymentEventFixtures.eventId(event))).isTrue();
    }

    @Test
    void unEventoQueNoCuadraConLaContratacionNoAutorizaElInicio() {
        for (String field : new String[]{"clientId", "providerId", "serviceRequestId"}) {
            ObjectNode event = PaymentEventFixtures.processed(contract, 2);
            ((ObjectNode) event.get("data")).put(field, UUID.randomUUID().toString());

            assertThat(handle(event)).as(field).isEqualTo(Outcome.REJECTED);
        }
        assertThat(payments.count()).isZero();
    }

    @Test
    void unaRetencionPorOtroValorOMonedaNoAutorizaElInicio() {
        ObjectNode otherPrice = PaymentEventFixtures.processed(contract, 2);
        ((ObjectNode) otherPrice.at("/data/breakdown/price")).put("amount", "1.00");
        ObjectNode otherCurrency = PaymentEventFixtures.processed(contract, 3);
        ((ObjectNode) otherCurrency.at("/data/breakdown/price")).put("currency", "USD");

        assertThat(handle(otherPrice)).isEqualTo(Outcome.REJECTED);
        assertThat(handle(otherCurrency)).isEqualTo(Outcome.REJECTED);

        assertThat(statusOfPayment()).isEqualTo(ContractPaymentStatus.PENDING);
    }

    @Test
    void elValorSeComparaPorMagnitudNoPorEscala() {
        ObjectNode event = PaymentEventFixtures.processed(contract, 2);
        ((ObjectNode) event.at("/data/breakdown/price")).put("amount", "105000");

        assertThat(handle(event)).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void unEventoRechazadoNoDejaAlPagoRetenidoAunqueAntesLoEstuviera() {
        handle(PaymentEventFixtures.failed(contract, 2));
        ObjectNode inconsistent = PaymentEventFixtures.processed(contract, 3);
        ((ObjectNode) inconsistent.get("data")).put("clientId", UUID.randomUUID().toString());

        assertThat(handle(inconsistent)).isEqualTo(Outcome.REJECTED);

        assertThat(statusOfPayment()).isEqualTo(ContractPaymentStatus.FAILED);
    }

    @Test
    void aUnHechoDeRetencionLeFaltanDatosDelContratoYNoQuedaNadaGuardado() {
        ObjectNode event = PaymentEventFixtures.processed(contract, 2);
        ((ObjectNode) event.get("data")).remove("contractId");
        ObjectNode noBreakdown = PaymentEventFixtures.processed(contract, 3);
        ((ObjectNode) noBreakdown.get("data")).remove("breakdown");

        assertThatThrownBy(() -> handle(event)).isInstanceOf(InvalidPaymentEventException.class);
        assertThatThrownBy(() -> handle(noBreakdown)).isInstanceOf(InvalidPaymentEventException.class);

        assertThat(payments.count()).isZero();
        assertThat(processedEvents.count()).isZero();
    }

    @Test
    void elPagoSeBorraConLaContratacion() {
        handle(PaymentEventFixtures.processed(contract, 2));

        requests.deleteAllInBatch();

        assertThat(payments.count()).isZero();
    }
}
