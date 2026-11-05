package com.fixia.services.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fixia.services.domain.ApproximateLocation;
import com.fixia.services.domain.CurrencyCode;
import com.fixia.services.domain.ExactLocation;
import com.fixia.services.domain.Quotation;
import com.fixia.services.domain.ServiceCategory;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.repository.QuotationRepository;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceRequestRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Contratos y eventos de pago para las pruebas del consumidor (GC-490). Los eventos tienen la forma de los esquemas de
 * {@code ms-payments/docs/events} (sobre v1); las pruebas de contrato usan además los ejemplos reales de ms-payments,
 * copiados en {@code src/test/resources/payments-events}.
 */
public final class PaymentEventFixtures {

    public static final UUID CLIENT = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    public static final UUID PROVIDER = UUID.fromString("2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f");
    public static final BigDecimal PRICE = new BigDecimal("105000.00");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PaymentEventFixtures() {
    }

    /** Solicitud contratada con una cotización aceptada y su contratación formalizada, todo guardado. */
    public static ServiceContract contract(ServiceRequestRepository requests, QuotationRepository quotations,
                                           ServiceContractRepository contracts) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ServiceRequest request = ServiceRequest.publish(CLIENT, ServiceCategory.PLUMBING, "Fuga de agua",
                new ApproximateLocation("Bogotá", "Chapinero"), new ExactLocation("Calle 10 # 5-20"),
                now.plus(Duration.ofDays(5)), new BigDecimal("200000"), CurrencyCode.COP, List.of(), now);
        request.selectProvider(PROVIDER, now);
        ServiceRequest savedRequest = requests.saveAndFlush(request);
        Quotation quotation = Quotation.send(savedRequest, PROVIDER, PRICE, CurrencyCode.COP, "Cambio de tubería",
                "Incluye materiales", now.plus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)), 1, now);
        quotation.accept(now);
        Quotation savedQuotation = quotations.saveAndFlush(quotation);
        savedRequest.contract(now);
        savedRequest = requests.saveAndFlush(savedRequest);
        return contracts.saveAndFlush(ServiceContract.formalize(savedRequest, savedQuotation, now));
    }

    /** {@code pago.procesado}: el total (precio + 15 % de comisión) quedó retenido. */
    public static ObjectNode processed(ServiceContract contract, long sequence) {
        ObjectNode event = envelope("pago.procesado", sequence, contract);
        ObjectNode data = data(contract, "Processing");
        data.set("breakdown", breakdown(contract));
        event.set("data", data);
        return event;
    }

    /** {@code pago.fallido}: no alcanzó el saldo y no se retuvo nada. */
    public static ObjectNode failed(ServiceContract contract, long sequence) {
        ObjectNode event = envelope("pago.fallido", sequence, contract);
        ObjectNode data = data(contract, "Failed");
        data.put("failureReason", "Saldo disponible insuficiente: hay 50000.00 COP y se necesitan 120750.00.");
        event.set("data", data);
        return event;
    }

    /** {@code pago.completado}: se cobró lo retenido al cerrar el servicio; no decide el inicio. */
    public static ObjectNode completed(ServiceContract contract, long sequence) {
        ObjectNode event = envelope("pago.completado", sequence, contract);
        ObjectNode data = data(contract, "Completed");
        data.put("transactionReference", "TX-1");
        event.set("data", data);
        return event;
    }

    public static UUID eventId(ObjectNode event) {
        return UUID.fromString(event.get("eventId").asText());
    }

    private static ObjectNode envelope(String type, long sequence, ServiceContract contract) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", type);
        event.put("schemaVersion", 1);
        event.put("occurredAt", "2026-11-03T17:45:00.120000Z");
        event.put("producer", "ms-payments");
        ObjectNode aggregate = event.putObject("aggregate");
        aggregate.put("type", "Payment");
        aggregate.put("id", UUID.nameUUIDFromBytes(contract.getId().toString().getBytes()).toString());
        aggregate.put("sequence", sequence);
        event.put("correlationId", contract.getServiceRequestId().toString());
        ObjectNode actor = event.putObject("actor");
        actor.putNull("userId");
        actor.put("role", "SYSTEM");
        return event;
    }

    private static ObjectNode data(ServiceContract contract, String status) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("paymentId", UUID.nameUUIDFromBytes(contract.getId().toString().getBytes()).toString());
        data.put("serviceRequestId", contract.getServiceRequestId().toString());
        data.put("contractId", contract.getId().toString());
        data.put("clientId", contract.getClientId().toString());
        data.put("providerId", contract.getProviderId().toString());
        ObjectNode amount = data.putObject("amount");
        amount.put("amount", contract.getAmount().multiply(new BigDecimal("1.15")).setScale(2, RoundingMode.HALF_UP).toPlainString());
        amount.put("currency", contract.getCurrency().name());
        data.put("status", status);
        return data;
    }

    private static ObjectNode breakdown(ServiceContract contract) {
        ObjectNode breakdown = MAPPER.createObjectNode();
        ObjectNode price = breakdown.putObject("price");
        price.put("amount", contract.getAmount().setScale(2).toPlainString());
        price.put("currency", contract.getCurrency().name());
        ObjectNode commission = breakdown.putObject("commission");
        commission.put("amount", contract.getAmount().multiply(new BigDecimal("0.15")).setScale(2, RoundingMode.HALF_UP).toPlainString());
        commission.put("currency", contract.getCurrency().name());
        breakdown.put("rate", "0.1500");
        breakdown.put("ruleId", "00000000-0000-0000-0000-000000000015");
        return breakdown;
    }
}
