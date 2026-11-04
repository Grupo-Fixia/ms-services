package com.fixia.services.consumer;

import com.fixia.services.domain.ContractPayment;
import com.fixia.services.domain.ContractPaymentStatus;
import com.fixia.services.domain.ProcessedEvent;
import com.fixia.services.domain.ServiceContract;
import com.fixia.services.repository.ContractPaymentRepository;
import com.fixia.services.repository.ProcessedEventRepository;
import com.fixia.services.repository.ServiceContractRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Refleja en cada contratación lo que ms-payments confirmó del pago virtual (GC-490). ms-services no genera saldo ni
 * decide si alcanza: solo recuerda si el total quedó retenido ({@code pago.procesado}) o no ({@code pago.fallido}), y
 * con eso {@code ContractService.start} permite o impide iniciar el servicio.
 *
 * <p>Reglas, en este orden y todas en una sola transacción con el registro del evento como procesado:
 * <ol>
 *   <li>Un {@code eventId} ya visto no hace nada: Kafka entrega al menos una vez.
 *   <li>Los demás hechos de pagos ({@code pago.creado}, {@code pago.completado}, {@code pago.reembolsado}) no deciden el
 *       inicio y solo se registran.
 *   <li>El hecho debe cuadrar con la contratación (solicitud, cliente, prestador y, para la retención, valor y moneda).
 *       Si no existe o no cuadra no se autoriza nada: se registra el error y se descarta, porque reintentarlo no lo arregla.
 *   <li>Un hecho con secuencia menor o igual a la última aplicada llegó tarde: se ignora, así un {@code pago.fallido}
 *       atrasado nunca deshace una retención confirmada.
 *   <li>{@code pago.procesado} deja el pago {@code HELD}; {@code pago.fallido}, {@code FAILED}. Tras un fallo el cliente
 *       puede recargar y reintentar, y el siguiente {@code pago.procesado} lo pasa a {@code HELD}.
 * </ol>
 */
@Service
public class PaymentEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    /** Qué se hizo con el evento. */
    public enum Outcome {
        /** Cambió el estado del pago de la contratación. */
        APPLIED,
        /** Ya se había procesado este {@code eventId}. */
        DUPLICATE,
        /** Un hecho de pagos que no decide el inicio del servicio. */
        NOT_RELEVANT,
        /** Llegó después de un hecho más nuevo del mismo pago. */
        STALE,
        /** No existe la contratación o los datos no cuadran con ella: no autoriza nada. */
        REJECTED
    }

    private final ContractPaymentRepository payments;
    private final ServiceContractRepository contracts;
    private final ProcessedEventRepository processedEvents;
    private final Clock clock;

    public PaymentEventHandler(ContractPaymentRepository payments, ServiceContractRepository contracts,
                               ProcessedEventRepository processedEvents, Clock clock) {
        this.payments = payments;
        this.contracts = contracts;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    /**
     * @throws InvalidPaymentEventException si al hecho le faltan datos del contrato; en ese caso no queda nada guardado
     */
    @Transactional
    public Outcome handle(PaymentEvent event) {
        if (processedEvents.existsById(event.eventId())) {
            return Outcome.DUPLICATE;
        }
        Instant now = clock.instant();
        Outcome outcome = event.decidesStart() ? apply(event, now) : Outcome.NOT_RELEVANT;
        processedEvents.save(new ProcessedEvent(event.eventId(), event.eventType(), now));
        return outcome;
    }

    private Outcome apply(PaymentEvent event, Instant now) {
        UUID contractId = event.dataUuid("contractId");
        UUID paymentId = event.dataUuid("paymentId");
        Optional<ServiceContract> found = contracts.findById(contractId);
        if (found.isEmpty() || !matches(found.get(), event)) {
            log.error("Se descarta {} {}: no existe la contratación o los datos no cuadran con ella",
                    event.eventType(), event.eventId());
            return Outcome.REJECTED;
        }
        ContractPaymentStatus status = PaymentEvent.PROCESSED.equals(event.eventType())
                ? ContractPaymentStatus.HELD : ContractPaymentStatus.FAILED;
        Optional<ContractPayment> current = payments.findById(contractId);
        if (current.isPresent()) {
            if (!current.get().isNewer(event.sequence())) {
                log.warn("Se ignora {} {}: llegó después de un hecho más nuevo del mismo pago",
                        event.eventType(), event.eventId());
                return Outcome.STALE;
            }
            current.get().apply(paymentId, status, event.eventId(), event.sequence(), now);
        } else {
            payments.save(ContractPayment.first(contractId, found.get().getServiceRequestId(), paymentId, status,
                    event.eventId(), event.sequence(), now));
        }
        return Outcome.APPLIED;
    }

    /**
     * El hecho es de esta contratación: misma solicitud, cliente y prestador. Para una retención, además, el valor sin
     * comisión ({@code breakdown.price}) y la moneda son los acordados: un evento inconsistente no autoriza el inicio.
     */
    private boolean matches(ServiceContract contract, PaymentEvent event) {
        boolean sameParties = contract.getServiceRequestId().equals(event.dataUuid("serviceRequestId"))
                && contract.getClientId().equals(event.dataUuid("clientId"))
                && contract.getProviderId().equals(event.dataUuid("providerId"));
        if (!sameParties || !PaymentEvent.PROCESSED.equals(event.eventType())) {
            return sameParties;
        }
        PaymentEvent.Money price = event.dataMoney(event.data().path("breakdown"), "price");
        return contract.getAmount().compareTo(price.amount()) == 0
                && contract.getCurrency().name().equals(price.currency());
    }
}
