package com.fixia.services.domain;

/**
 * Estado del pago virtual de una contratación según lo que ms-payments confirmó (GC-490). Solo {@link #HELD} autoriza
 * el inicio del servicio.
 */
public enum ContractPaymentStatus {

    /** ms-payments todavía no ha confirmado nada: no hay fila de pago. No autoriza el inicio. */
    PENDING,

    /** El total quedó retenido en la billetera del cliente ({@code pago.procesado}). Autoriza el inicio. */
    HELD,

    /** No se pudo retener, p. ej. por saldo insuficiente ({@code pago.fallido}). Puede pasar a {@link #HELD} si el cliente recarga y reintenta. */
    FAILED
}
