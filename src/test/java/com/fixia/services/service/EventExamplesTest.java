package com.fixia.services.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los ejemplos del flujo completo de {@code docs/events/examples} son lo que otros equipos (el lakehouse, GC-435) toman como
 * referencia: deben cumplir los esquemas y formar una sola historia coherente de la misma solicitud, en orden.
 */
class EventExamplesTest {

    private static final Path EXAMPLES = Path.of("docs", "events", "examples", "flujo-completo");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<JsonNode> examples() throws IOException {
        try (Stream<Path> files = Files.list(EXAMPLES)) {
            List<Path> sorted = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
            List<JsonNode> documents = new java.util.ArrayList<>();
            for (Path file : sorted) {
                documents.add(objectMapper.readTree(file.toFile()));
            }
            return documents;
        }
    }

    @Test
    void cadaEjemploCumpleElEsquemaDeSuEvento() throws IOException {
        for (JsonNode example : examples()) {
            assertThat(EventContract.violations(example)).as(example.get("eventType").asText()).isEmpty();
        }
    }

    @Test
    void losEjemplosSonLaHistoriaOrdenadaDeUnaSolaSolicitud() throws IOException {
        List<JsonNode> examples = examples();

        assertThat(examples).isNotEmpty();
        for (int index = 0; index < examples.size(); index++) {
            JsonNode example = examples.get(index);
            assertThat(example.at("/aggregate/sequence").asInt()).isEqualTo(index + 1);
            assertThat(example.at("/aggregate/id").asText())
                    .isEqualTo(examples.get(0).at("/aggregate/id").asText());
            assertThat(example.get("correlationId").asText()).isEqualTo(example.at("/aggregate/id").asText());
            if (index > 0) {
                assertThat(example.get("occurredAt").asText())
                        .isGreaterThanOrEqualTo(examples.get(index - 1).get("occurredAt").asText());
            }
        }
        assertThat(examples).extracting(example -> example.get("eventType").asText()).endsWith(
                "servicio.contratado", "servicio.iniciado", "servicio.finalizado", "servicio.cierre_objetado",
                "servicio.finalizado", "servicio.cerrado");
    }
}
