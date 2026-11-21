package com.fixia.services.relay;

import com.fixia.services.domain.OutboxEvent;
import com.fixia.services.domain.OutboxStatus;
import com.fixia.services.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Servicio encargado de procesar y publicar los eventos pendientes de la tabla Outbox a Kafka (GC-434).
 * <p>
 * Reglas de negocio:
 * <ul>
 *   <li>Selecciona registros con {@code FOR UPDATE SKIP LOCKED} para gestionar concurrencia multi-instancia.</li>
 *   <li>Mantiene orden estricto por agregado (no publica secuencia n+1 mientras la secuencia anterior no esté {@code PUBLISHED}).</li>
 *   <li>Marca {@code PUBLISHED} solo tras el acuse de recibo de Kafka.</li>
 *   <li>Aplica política de reintentos con backoff exponencial y estado {@code FAILED} tras 10 intentos.</li>
 *   <li>No loguea contenido de eventos, contraseñas, ni tokens de seguridad.</li>
 * </ul>
 */
@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile("(?i)(bearer\\s+[a-z0-9._\\-]+|token=[^,\\s&]+|password=[^,\\s&]+|secret=[^,\\s&]+)");

    private final OutboxEventRepository repository;
    private final OutboxPublisher publisher;
    private final OutboxMetrics metrics;
    private final OutboxRelayProperties properties;

    public OutboxRelayService(OutboxEventRepository repository,
                              OutboxPublisher publisher,
                              OutboxMetrics metrics,
                              OutboxRelayProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * Procesa un lote de eventos pendientes del Outbox en una transacción de base de datos.
     *
     * <p>La consulta solo entrega, por solicitud, el primer evento pendiente (el orden por agregado es estricto). Para
     * no publicar un solo evento por solicitud en cada ronda (GC-505), se hacen varias pasadas dentro de la misma
     * transacción: tras cada pasada se sincroniza con la base y la siguiente ya ve publicados los anteriores, así que
     * toma la secuencia siguiente. Se detiene al llenar el lote, cuando no quedan pendientes o cuando una pasada no
     * logra publicar nada (por ejemplo, Kafka no responde), para no insistir en la misma ronda.
     *
     * @param batchSize Número máximo de eventos a procesar en esta iteración.
     * @return Cantidad de eventos procesados (publicados o fallidos).
     */
    @Transactional
    public int processBatch(int batchSize) {
        int processed = 0;
        while (processed < batchSize) {
            List<OutboxEvent> candidates = repository.findPendingForRelay(Instant.now(), batchSize - processed);
            if (candidates.isEmpty()) {
                break;
            }

            log.debug("Procesando {} eventos pendientes del Outbox", candidates.size());

            boolean anyPublished = false;
            for (OutboxEvent event : candidates) {
                publishSingleEvent(event);
                repository.save(event);
                anyPublished |= event.getStatus() == OutboxStatus.PUBLISHED;
            }
            processed += candidates.size();
            if (!anyPublished) {
                break;
            }
            // La siguiente consulta (nativa) debe ver como PUBLISHED lo que se acaba de publicar.
            repository.flush();
        }
        return processed;
    }

    private void publishSingleEvent(OutboxEvent event) {
        Instant now = Instant.now();
        try {
            log.debug("Publicando evento: eventId={}, tipo={}, seq={}, aggregateId={}",
                    event.getEventId(), event.getEventType(), event.getAggregateSequence(), event.getAggregateId());

            publisher.publish(event).get(properties.getPublishTimeoutSeconds(), TimeUnit.SECONDS);

            event.markPublished(now);
            metrics.recordPublished();

            log.info("Evento del Outbox publicado exitosamente: eventId={}, tipo={}, seq={}",
                    event.getEventId(), event.getEventType(), event.getAggregateSequence());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleFailure(event, "Publicación interrumpida en el hilo de ejecución", now);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            handleFailure(event, cause.getMessage(), now);
        } catch (TimeoutException e) {
            handleFailure(event, "Tiempo de espera agotado al esperar acuse de Kafka (" + properties.getPublishTimeoutSeconds() + "s)", now);
        } catch (Exception e) {
            handleFailure(event, e.getMessage(), now);
        }
    }

    private void handleFailure(OutboxEvent event, String rawError, Instant now) {
        String sanitizedError = sanitizeError(rawError);
        event.registerFailedAttempt(sanitizedError, now);
        metrics.recordRetry();

        if (event.getStatus() == OutboxStatus.FAILED) {
            metrics.recordFailed();
            log.error("Evento del Outbox superó el límite de reintentos ({}) y quedó en estado FAILED: eventId={}, tipo={}, error={}",
                    OutboxEvent.MAX_ATTEMPTS, event.getEventId(), event.getEventType(), sanitizedError);
        } else {
            log.warn("Fallo temporal al publicar evento del Outbox: eventId={}, tipo={}, intentos={}, próximoIntento={}, error={}",
                    event.getEventId(), event.getEventType(), event.getAttempts(), event.getNextAttemptAt(), sanitizedError);
        }
    }

    static String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return "Error desconocido en publicación a broker";
        }
        String sanitized = TOKEN_PATTERN.matcher(error).replaceAll("[REDACTED]");
        if (sanitized.length() > OutboxEvent.MAX_ERROR_LENGTH) {
            return sanitized.substring(0, OutboxEvent.MAX_ERROR_LENGTH);
        }
        return sanitized;
    }
}

