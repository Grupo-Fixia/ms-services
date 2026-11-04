package com.fixia.services.service;

import com.fixia.services.exception.InvalidHistoryQueryException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cursor del historial (GC-448): ida y vuelta, y rechazo de todo lo que esta API no pudo haber entregado. */
class HistoryCursorTest {

    private static String encoded(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void codificarYDecodificarDevuelveLaMismaClave() {
        HistoryCursor cursor = new HistoryCursor(Instant.parse("2026-10-30T15:20:10.123456Z"), UUID.randomUUID());

        assertThat(HistoryCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void esTextoOpacoSinCaracteresQueRompanUnaUrl() {
        String text = new HistoryCursor(Instant.parse("2026-10-30T15:20:10Z"), UUID.randomUUID()).encode();

        assertThat(text).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void rechazaTextoQueNoEsUnCursor() {
        UUID id = UUID.randomUUID();
        List<String> invalid = List.of(
                "", "no-es-un-cursor", "%%%",
                encoded("sin-separador"),
                encoded("|" + id),
                encoded("2026-10-30T15:20:10Z|"),
                encoded("no-es-fecha|" + id),
                encoded("2026-10-30T15:20:10Z|no-es-uuid"),
                // la fecha queda fuera del rango que esta API puede haber entregado
                encoded("1999-12-31T23:59:59Z|" + id),
                encoded("2200-01-01T00:00:01Z|" + id),
                // un UUID que Java acepta a medias (sin guiones completos) no es el que se entregó
                encoded("2026-10-30T15:20:10Z|1-1-1-1-1"));

        for (String text : invalid) {
            assertThatThrownBy(() -> HistoryCursor.decode(text)).as(text.isEmpty() ? "(vacío)" : text)
                    .isInstanceOf(InvalidHistoryQueryException.class).hasMessage("El cursor no es válido");
        }
    }
}
