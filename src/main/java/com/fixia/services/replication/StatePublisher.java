package com.fixia.services.replication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.repository.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Publica el estado de las entidades que otros servicios necesitan (GC-547, cero dependencias síncronas). Para cada
 * {@link StateSource} compara el estado actual de cada entidad con el hash de lo último publicado y escribe en el Outbox
 * solo lo que cambió, en la misma transacción que actualiza {@code state_publications}. El relay de siempre lo lleva a
 * Kafka con la entidad como clave.
 *
 * <ul>
 *   <li>Una entidad que deja de existir se publica con {@code removed=true}.
 *   <li>Cada {@code fixia.replication.refresh} (6 h por defecto) se vuelve a publicar todo, aunque no haya cambiado, para
 *       que un consumidor nuevo lo encuentre aunque Kafka ya haya borrado la versión anterior por retención.
 *   <li>La secuencia es el instante de publicación en milisegundos (nunca menor que la anterior): el consumidor descarta
 *       una versión más vieja que la que ya tiene.
 * </ul>
 */
@Component
public class StatePublisher {

    static final short V1 = 1;

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .findAndAddModules()
            // Propiedades y llaves en orden: el mismo estado da siempre el mismo texto y el mismo hash.
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .build();

    private final List<StateSource> sources;
    private final StatePublicationRepository publications;
    private final OutboxEventRepository outbox;
    private final Clock clock;
    private final Duration refresh;
    private final String producer;

    public StatePublisher(List<StateSource> sources, StatePublicationRepository publications,
                          OutboxEventRepository outbox, Clock clock,
                          @Value("${fixia.replication.refresh:PT6H}") Duration refresh,
                          @Value("${spring.application.name}") String producer) {
        this.sources = sources;
        this.publications = publications;
        this.outbox = outbox;
        this.clock = clock;
        this.refresh = refresh;
        this.producer = producer;
    }

    /** @return cuántos estados se escribieron en el Outbox */
    @Transactional
    public int publishChanges() {
        int written = 0;
        for (StateSource source : sources) {
            written += publish(source);
        }
        return written;
    }

    private int publish(StateSource source) {
        Instant now = clock.instant();
        Map<UUID, StatePublication> published = publications.findByIdStateType(source.stateType()).stream()
                .collect(Collectors.toMap(publication -> publication.getId().getStateKey(), Function.identity()));
        int written = 0;
        for (Map.Entry<UUID, Object> entry : source.currentStates().entrySet()) {
            JsonNode data = CANONICAL.valueToTree(entry.getValue());
            String hash = sha256(write(data));
            StatePublication previous = published.remove(entry.getKey());
            boolean changed = previous == null || !previous.getHash().equals(hash)
                    || previous.getPublishedAt().plus(refresh).isBefore(now);
            if (!changed) {
                continue;
            }
            long sequence = nextSequence(previous, now);
            outbox.save(event(source, entry.getKey(), sequence, false, data, now));
            if (previous == null) {
                publications.save(new StatePublication(new StateKey(source.stateType(), entry.getKey()), hash,
                        sequence, now));
            } else {
                previous.update(hash, sequence, now);
            }
            written++;
        }
        for (StatePublication gone : published.values()) {
            outbox.save(event(source, gone.getId().getStateKey(), nextSequence(gone, now), true,
                    CANONICAL.createObjectNode(), now));
            publications.delete(gone);
            written++;
        }
        return written;
    }

    private static long nextSequence(StatePublication previous, Instant now) {
        long millis = now.toEpochMilli();
        return previous == null ? millis : Math.max(millis, previous.getSequence() + 1);
    }

    private OutboxEvent event(StateSource source, UUID key, long sequence, boolean removed, JsonNode data,
                              Instant now) {
        UUID eventId = UUID.randomUUID();
        ObjectNode envelope = CANONICAL.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", source.stateType());
        envelope.put("schemaVersion", V1);
        envelope.put("occurredAt", now.toString());
        envelope.put("producer", producer);
        ObjectNode aggregate = envelope.putObject("aggregate");
        aggregate.put("type", source.aggregateType());
        aggregate.put("id", key.toString());
        aggregate.put("sequence", sequence);
        envelope.put("removed", removed);
        envelope.set("data", data);
        return OutboxEvent.pending(eventId, source.stateType(), V1, source.aggregateType(), key, sequence,
                source.topic(), key.toString(), write(envelope), now);
    }

    private static String write(JsonNode node) {
        try {
            return CANONICAL.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar un estado replicado", e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
