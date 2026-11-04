package com.fixia.services.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * El consumidor de pagos (GC-490) de punta a punta contra un broker Kafka en memoria: se publica en
 * {@code fixia.payments.events} lo que publica ms-payments y la contratación queda reflejada, sin repetir efectos ante
 * reentregas y sin trabarse con un mensaje que no cumple el contrato.
 */
@SpringBootTest(properties = {
        "fixia.payments.consumer.enabled=true",
        "fixia.payments.consumer.group-id=ms-services-test",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@EmbeddedKafka(partitions = 1, topics = "fixia.payments.events")
@ActiveProfiles("test")
@DirtiesContext
class PaymentConsumerKafkaTest {

    private static final String TOPIC = "fixia.payments.events";

    @Autowired
    private KafkaTemplate<String, String> kafka;

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
    void prepare() {
        processedEvents.deleteAllInBatch();
        requests.deleteAllInBatch();
        contract = PaymentEventFixtures.contract(requests, quotations, contracts);
    }

    private void publish(String key, String value) throws Exception {
        kafka.send(TOPIC, key, value).get();
    }

    private ContractPaymentStatus statusOfPayment() {
        return payments.findById(contract.getId()).map(ContractPayment::getStatus).orElse(ContractPaymentStatus.PENDING);
    }

    /** El ejemplo real de ms-payments, apuntando a la contratación de la prueba. */
    private ObjectNode realExample(String name) throws Exception {
        ObjectNode event = (ObjectNode) mapper.readTree(PaymentEventTest.example(name));
        ObjectNode data = (ObjectNode) event.get("data");
        data.put("contractId", contract.getId().toString());
        data.put("serviceRequestId", contract.getServiceRequestId().toString());
        return event;
    }

    @Test
    void elEjemploRealDePagoProcesadoDejaLaContratacionRetenidaYUnaReentregaNoHaceNada() throws Exception {
        ObjectNode event = realExample("02-pago.procesado.json");

        publish("payment-1", event.toString());
        await().atMost(Duration.ofSeconds(30)).until(() -> statusOfPayment() == ContractPaymentStatus.HELD);

        // La reentrega (mismo eventId) y, después, un mensaje roto y uno válido: el consumidor sigue vivo.
        publish("payment-1", event.toString());
        publish("payment-1", "{esto no es un evento");
        ObjectNode failedLater = realExample("02-alt-pago.fallido-sin-saldo.json");
        failedLater.put("eventId", UUID.randomUUID().toString());
        ((ObjectNode) failedLater.get("aggregate")).put("sequence", 3);
        publish("payment-1", failedLater.toString());
        await().atMost(Duration.ofSeconds(30)).until(() -> statusOfPayment() == ContractPaymentStatus.FAILED);

        assertThat(payments.count()).isEqualTo(1);
        assertThat(processedEvents.count()).isEqualTo(2);
        assertThat(payments.findById(contract.getId()).orElseThrow().getLastSequence()).isEqualTo(3);
    }
}
