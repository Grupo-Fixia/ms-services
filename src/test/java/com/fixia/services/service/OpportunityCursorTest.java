package com.fixia.services.service;

import com.fixia.services.exception.InvalidOpportunityQueryException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El cursor de oportunidades (GC-338): ida y vuelta exacta y rechazo de cualquier texto que no lo sea. */
class OpportunityCursorTest {

    private static String encoded(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void idaYVueltaConservaElInstanteConMicrosegundosYElId() {
        OpportunityCursor cursor = new OpportunityCursor(Instant.parse("2026-10-20T19:56:32.405736Z"), UUID.randomUUID());

        assertThat(OpportunityCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void elTextoEsOpacoYSeguroParaUnaUrl() {
        String text = new OpportunityCursor(Instant.now(), UUID.randomUUID()).encode();

        assertThat(text).matches("[A-Za-z0-9_-]+").doesNotContain("|").doesNotContain("=");
    }

    @Test
    void rechazaTextoQueNoEsUnCursor() {
        UUID id = UUID.randomUUID();
        String[] invalid = {
                "no es base64!!",
                "",
                encoded("sin separador"),
                encoded("|" + id),
                encoded("2026-10-20T19:56:32Z|"),
                encoded("no-es-una-fecha|" + id),
                encoded("2026-10-20T19:56:32Z|no-es-uuid"),
                encoded("2026-10-20T19:56:32Z|1-1-1-1-1"),
                encoded("+999999999-12-31T23:59:59Z|" + id),
                encoded("-999999999-01-01T00:00:00Z|" + id),
                encoded("1999-12-31T23:59:59Z|" + id),
        };

        for (String text : invalid) {
            assertThatThrownBy(() -> OpportunityCursor.decode(text))
                    .as("cursor %s", text)
                    .isInstanceOf(InvalidOpportunityQueryException.class)
                    .hasMessage("El cursor no es válido");
        }
    }
}
