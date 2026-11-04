package com.fixia.services.service;

import com.fixia.services.domain.ServiceContract;
import com.fixia.services.exception.InvalidHistoryQueryException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Posición en el historial de servicios (GC-448): la clave de orden {@code (contractedAt, id)} de la última
 * contratación entregada. El historial se recorre de la más reciente a la más antigua, así que la página siguiente son
 * las contrataciones estrictamente anteriores a esta clave; el {@code id} desempata las que se formalizaron en el mismo
 * instante.
 *
 * <p>Viaja como texto opaco (Base64 URL de {@code <instante>|<id>}). No va firmado: solo contiene la clave de orden de
 * una contratación y un cursor manipulado solo cambia el punto de partida, nunca de quién es lo que se lista, que sale
 * siempre del token.
 */
public record HistoryCursor(Instant contractedAt, UUID id) {

    private static final char SEPARATOR = '|';

    /** Rango razonable de {@code contractedAt}; fuera de él el cursor no pudo salir de esta API y se rechaza. */
    private static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2200-01-01T00:00:00Z");

    public static HistoryCursor of(ServiceContract contract) {
        return new HistoryCursor(contract.getContractedAt(), contract.getId());
    }

    public String encode() {
        String raw = contractedAt + String.valueOf(SEPARATOR) + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidHistoryQueryException si el texto no es un cursor válido */
    public static HistoryCursor decode(String text) {
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
            Instant contractedAt = Instant.parse(raw.substring(0, separator));
            if (contractedAt.isBefore(EARLIEST) || contractedAt.isAfter(LATEST)) {
                throw invalid();
            }
            return new HistoryCursor(contractedAt, id);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static InvalidHistoryQueryException invalid() {
        return new InvalidHistoryQueryException("El cursor no es válido");
    }
}
