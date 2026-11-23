package com.fixia.services.revocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Map;

/**
 * Llena la {@link RevocationList} con {@code sesion.revocada} de {@code fixia.users.sessions} (GC-547).
 *
 * <ul>
 *   <li><b>Cada instancia lee todo:</b> su grupo es único (sufijo aleatorio) y nunca confirma offsets.
 *   <li><b>Al arrancar</b> retrocede {@code fixia.revocations.lookback} (20 min, más que la vida del access token) para
 *       reconstruir la lista.
 *   <li><b>Al día:</b> tras cada segundo sin mensajes el contenedor avisa que está inactivo y aquí se pregunta al broker
 *       el último offset de cada partición. Solo si la posición lo alcanzó se marca la lista como al día. Si el broker
 *       no responde no se marca, y en pocos segundos la lista deja de usarse: un Kafka caído no hace pasar por buena
 *       una lista vieja.
 * </ul>
 * Un mensaje que no cumple el contrato se registra, sin su contenido, y se salta.
 */
@Component
@ConditionalOnProperty(prefix = "fixia.revocations.consumer", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SessionRevocationListener implements ConsumerSeekAware {

    static final String LISTENER_ID = "session-revocation-listener";
    private static final String SESSION_REVOKED = "sesion.revocada";
    private static final Duration END_OFFSETS_TIMEOUT = Duration.ofMillis(500);

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationListener.class);

    private final RevocationList list;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration lookback;

    public SessionRevocationListener(RevocationList list, ObjectMapper objectMapper, Clock clock,
                                     @Value("${fixia.revocations.lookback:PT20M}") Duration lookback) {
        this.list = list;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.lookback = lookback;
    }

    @KafkaListener(id = LISTENER_ID, containerFactory = "revocationContainerFactory",
            topics = "${fixia.revocations.consumer.topic:fixia.users.sessions}",
            groupId = "${fixia.revocations.consumer.group-prefix:ms-services-revocations}-${random.uuid}")
    public void onMessage(ConsumerRecord<String, String> record) {
        try {
            JsonNode root = objectMapper.readTree(record.value());
            if (root == null || !SESSION_REVOKED.equals(root.path("eventType").asText(null))) {
                return;
            }
            JsonNode data = root.path("data");
            String jti = data.path("jti").asText(null);
            String expiresAt = data.path("expiresAt").asText(null);
            if (jti == null || jti.isBlank() || expiresAt == null) {
                throw new IllegalArgumentException("Falta data.jti o data.expiresAt");
            }
            list.add(jti, Instant.parse(expiresAt));
        } catch (DateTimeParseException | IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // Solo dónde estaba el mensaje: nunca su contenido.
            log.error("Mensaje inválido en {}-{}@{}, se salta ({})", record.topic(), record.partition(),
                    record.offset(), e.getClass().getSimpleName());
        }
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        callback.seekToTimestamp(assignments.keySet(), clock.instant().minus(lookback).toEpochMilli());
    }

    /** Se ejecuta en el hilo del consumidor: ahí es seguro usarlo. */
    @EventListener(condition = "event.listenerId.startsWith('" + LISTENER_ID + "')")
    public void onIdle(ListenerContainerIdleEvent event) {
        Consumer<?, ?> consumer = event.getConsumer();
        Collection<TopicPartition> partitions = consumer.assignment();
        if (partitions.isEmpty()) {
            return;
        }
        try {
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, END_OFFSETS_TIMEOUT);
            boolean caughtUp = partitions.stream()
                    .allMatch(partition -> consumer.position(partition, END_OFFSETS_TIMEOUT) >= ends.get(partition));
            if (caughtUp) {
                list.markCaughtUp();
            }
        } catch (RuntimeException e) {
            // El broker no respondió: no se marca, y la lista envejece hasta dejar de usarse.
            log.debug("No se pudo confirmar que la lista de revocados está al día ({})", e.getClass().getSimpleName());
        }
    }
}
