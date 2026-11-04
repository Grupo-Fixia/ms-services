package com.fixia.services.exception;

import com.fixia.services.domain.ContractPaymentStatus;

/**
 * No se puede iniciar el servicio porque ms-payments no ha confirmado el saldo retenido (GC-490). El mensaje es claro
 * para el prestador y no revela cifras ni datos de la billetera del cliente.
 */
public class PaymentNotRetainedException extends RuntimeException {

    private final ContractPaymentStatus paymentStatus;

    public PaymentNotRetainedException(ContractPaymentStatus paymentStatus) {
        super(messageFor(paymentStatus));
        this.paymentStatus = paymentStatus;
    }

    private static String messageFor(ContractPaymentStatus status) {
        if (status == ContractPaymentStatus.FAILED) {
            return "El servicio no puede iniciar: no se pudo retener el pago del cliente, por ejemplo por saldo "
                    + "insuficiente. Cuando el cliente recargue su billetera y el pago se retenga, podrás iniciarlo";
        }
        return "El servicio no puede iniciar todavía: el pago del cliente aún no está retenido. "
                + "Intenta de nuevo cuando se confirme";
    }

    public ContractPaymentStatus getPaymentStatus() {
        return paymentStatus;
    }
}
