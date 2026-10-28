package com.fixia.services.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estado de una solicitud de servicio y sus transiciones permitidas.
 *
 * <pre>
 * PUBLISHED           -> PROVIDER_SELECTED, CANCELLED, EXPIRED
 * PROVIDER_SELECTED   -> CONTRACTED, CANCELLED
 * CONTRACTED          -> IN_PROGRESS
 * IN_PROGRESS         -> TECHNICIAN_FINISHED
 * TECHNICIAN_FINISHED -> CLOSED, IN_PROGRESS (el cliente confirma el cierre o lo objeta)
 * CLOSED, CANCELLED, EXPIRED son terminales
 * </pre>
 *
 * <p>Un servicio {@code CLOSED} no se reabre: la objeción del cliente solo es posible mientras el técnico lo declara
 * finalizado, antes de confirmar el cierre.
 */
public enum ServiceRequestStatus {

    /** Publicada por el cliente y visible para prestadores compatibles. Las postulaciones se modelan aparte. */
    PUBLISHED,

    /** El cliente eligió un único prestador; la selección queda cerrada frente a los demás postulantes. */
    PROVIDER_SELECTED,

    /** Contratación formalizada: a partir de aquí la dirección exacta deja de estar protegida. */
    CONTRACTED,

    /** El prestador contratado inició el servicio (GC-437). La dirección exacta sigue disponible para los dos. */
    IN_PROGRESS,

    /**
     * El técnico declaró que terminó (GC-441). Todavía no es un cierre: falta que el cliente lo confirme, o lo objete y
     * el servicio vuelva a {@link #IN_PROGRESS}.
     */
    TECHNICIAN_FINISHED,

    /** El cliente confirmó el cierre (GC-441). Es definitivo y habilita la evaluación posterior. */
    CLOSED,

    CANCELLED,

    /** Venció el momento esperado sin que se formalizara la contratación. */
    EXPIRED;

    public Set<ServiceRequestStatus> allowedTransitions() {
        return switch (this) {
            case PUBLISHED -> EnumSet.of(PROVIDER_SELECTED, CANCELLED, EXPIRED);
            case PROVIDER_SELECTED -> EnumSet.of(CONTRACTED, CANCELLED);
            case CONTRACTED -> EnumSet.of(IN_PROGRESS);
            case IN_PROGRESS -> EnumSet.of(TECHNICIAN_FINISHED);
            case TECHNICIAN_FINISHED -> EnumSet.of(CLOSED, IN_PROGRESS);
            case CLOSED, CANCELLED, EXPIRED -> EnumSet.noneOf(ServiceRequestStatus.class);
        };
    }

    public boolean canTransitionTo(ServiceRequestStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    /**
     * Si desde este estado el prestador contratado y el cliente reciben la dirección exacta (ADR-009): desde la
     * contratación y también una vez cerrado el servicio, para que el contrato se consulte igual que uno en curso. Es el
     * único lugar que decide esto: cada etapa nueva se agrega aquí y no en cada consulta.
     */
    public boolean exposesExactLocation() {
        return this == CONTRACTED || this == IN_PROGRESS || this == TECHNICIAN_FINISHED || this == CLOSED;
    }
}
