package com.fixia.services.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Valida un sobre producido por el Outbox contra los esquemas del contrato de eventos ({@code docs/events/schemas},
 * GC-436). Los esquemas se leen del repositorio, así que si el código y el contrato se separan, las pruebas fallan.
 */
public final class EventContract {

    private static final String BASE = "https://fixia.local/schemas/events/";
    private static final Path SCHEMAS = Path.of("docs", "events", "schemas").toAbsolutePath();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(BASE, directoryUri())));
    private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder()
            .formatAssertionsEnabled(true)
            .build();

    private EventContract() {
    }

    private static String directoryUri() {
        if (!Files.isDirectory(SCHEMAS)) {
            throw new IllegalStateException("No se encontraron los esquemas en " + SCHEMAS);
        }
        String uri = SCHEMAS.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }

    /** Errores del sobre frente al esquema de su tipo y versión; vacío si lo cumple. */
    public static Set<String> violations(JsonNode envelope) {
        String file = envelope.path("eventType").asText() + ".v" + envelope.path("schemaVersion").asInt()
                + ".schema.json";
        if (!Files.exists(SCHEMAS.resolve(file))) {
            return Set.of("No hay esquema " + file);
        }
        JsonSchema schema = FACTORY.getSchema(SchemaLocation.of(BASE + file), CONFIG);
        Set<ValidationMessage> errors = schema.validate(envelope);
        return errors.stream().map(ValidationMessage::getMessage).collect(Collectors.toSet());
    }
}
