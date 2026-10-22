package com.fixia.services.service;

import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.exception.InvalidOpportunityQueryException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Posición en la lista de oportunidades (GC-338): la clave de orden {@code (publishedAt, id)} de la última candidata
 * revisada. La lista se recorre de la más reciente a la más antigua, así que la página siguiente son las candidatas
 * estrictamente anteriores a esta clave; el {@code id} desempata las que se publicaron en el mismo instante.
 *
 * <p>Viaja como texto opaco (Base64 URL de {@code <instante>|<id>}). No va firmado: solo contiene claves de orden de
 * solicitudes y un cursor manipulado solo cambia el punto de partida, nunca lo que ms-users confirma como elegible.
 */
public record OpportunityCursor(Instant publishedAt, UUID id) {

    private static final char SEPARATOR = '|';

    /** Rango razonable de {@code publishedAt}; fuera de él el cursor no pudo salir de esta API y se rechaza. */
    private static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2200-01-01T00:00:00Z");

    public static OpportunityCursor of(ServiceRequest request) {
        return new OpportunityCursor(request.getPublishedAt(), request.getId());
    }

    public String encode() {
        String raw = publishedAt + String.valueOf(SEPARATOR) + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidOpportunityQueryException si el texto no es un cursor válido */
    public static OpportunityCursor decode(String text) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            int separator = raw.indexOf(SEPARATOR);
            if (separator <= 0 || separator == raw.length() - 1) {
                throw invalid();
            }
            String idText = raw.substring(separator + 1);
            UUID id = UUID.fromString(idText);
            if (!id.toString().equalsIgnoreCase(idText)) {
                throw invalid();
            }
            Instant publishedAt = Instant.parse(raw.substring(0, separator));
            if (publishedAt.isBefore(EARLIEST) || publishedAt.isAfter(LATEST)) {
                throw invalid();
            }
            return new OpportunityCursor(publishedAt, id);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static InvalidOpportunityQueryException invalid() {
        return new InvalidOpportunityQueryException("El cursor no es válido");
    }
}
