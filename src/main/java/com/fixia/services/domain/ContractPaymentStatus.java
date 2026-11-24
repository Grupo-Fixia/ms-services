package com.fixia.services.domain;

/**
 * Estado del pago virtual de una contratación según lo que ms-payments confirmó (GC-490). Solo {@link #HELD} autoriza
 * el inicio del servicio. {@link #COMPLETED} y {@link #REFUNDED} reflejan cómo terminó el dinero retenido.
 */
public enum ContractPaymentStatus {

    /** ms-payments todavía no ha confirmado nada: no hay fila de pago. No autoriza el inicio. */
    PENDING,

    /** El total quedó retenido en la billetera del cliente ({@code pago.procesado}). Autoriza el inicio. */
    HELD,

    /** No se pudo retener, p. ej. por saldo insuficiente ({@code pago.fallido}). Puede pasar a {@link #HELD} si el cliente recarga y reintenta. */
    FAILED,

    /**
     * Se cobró lo retenido al cerrar el servicio ({@code pago.completado}): el prestador recibió el precio y Fixia la
     * comisión. Ya no autoriza el inicio.
     */
    COMPLETED,

    /**
     * El administrador devolvió el dinero al cliente ({@code pago.reembolsado}): se liberó la retención o se revirtió el
     * cobro. Ya no autoriza el inicio y el cierre no cobra nada.
     */
    REFUNDED
}
