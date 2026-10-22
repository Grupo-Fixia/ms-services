package com.fixia.services.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estado de una cotización y sus transiciones permitidas (RF-031 a RF-033).
 *
 * <pre>
 * SENT -> ACCEPTED, REJECTED, EXPIRED, SUPERSEDED
 * ACCEPTED, REJECTED, EXPIRED, SUPERSEDED son terminales
 * </pre>
 *
 * Una cotización enviada no se edita: si el prestador cambia las condiciones envía una nueva y la anterior queda
 * {@code SUPERSEDED}. Así ninguna cotización modificada puede quedar vigente sin una nueva aceptación (RF-033).
 *
 * <p>Como {@code ServiceCategory} en ms-users, en la API y en la base viaja el nombre de la constante en inglés; el
 * nombre en español es solo para mostrar (DD V2: ENVIADA, ACEPTADA, RECHAZADA, VENCIDA, REEMPLAZADA).
 */
public enum QuotationStatus {

    /** Enviada por el prestador seleccionado; espera la respuesta del cliente. */
    SENT("Enviada"),

    /** Aceptada por el cliente: sus condiciones son las que se formalizan en la contratación. */
    ACCEPTED("Aceptada"),

    /** Rechazada por el cliente. */
    REJECTED("Rechazada"),

    /** Pasó su vigencia sin respuesta del cliente. */
    EXPIRED("Vencida"),

    /** Reemplazada por una cotización más reciente del mismo prestador. */
    SUPERSEDED("Reemplazada");

    private final String displayName;

    QuotationStatus(String displayName) {
        this.displayName = displayName;
    }

    /** Nombre para mostrar; en la API y en la base viaja el nombre de la constante. */
    public String getDisplayName() {
        return displayName;
    }

    public Set<QuotationStatus> allowedTransitions() {
        return switch (this) {
            case SENT -> EnumSet.of(ACCEPTED, REJECTED, EXPIRED, SUPERSEDED);
            case ACCEPTED, REJECTED, EXPIRED, SUPERSEDED -> EnumSet.noneOf(QuotationStatus.class);
        };
    }

    public boolean canTransitionTo(QuotationStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
