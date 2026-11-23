package com.fixia.services.replication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ejecuta el {@link StatePublisher} cada {@code fixia.replication.publisher.interval-ms} (3 s por defecto). Con varias
 * réplicas del servicio puede salir un estado repetido: el consumidor lo ignora por su secuencia. Las pruebas lo
 * apagan con {@code fixia.replication.publisher.enabled=false} y llaman al publicador directamente.
 */
@Component
@ConditionalOnProperty(prefix = "fixia.replication.publisher", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class StatePublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatePublisherScheduler.class);

    private final StatePublisher publisher;

    public StatePublisherScheduler(StatePublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${fixia.replication.publisher.interval-ms:3000}",
            initialDelayString = "${fixia.replication.publisher.initial-delay-ms:5000}")
    public void run() {
        try {
            int written = publisher.publishChanges();
            if (written > 0) {
                log.debug("Estados replicados escritos en el Outbox: {}", written);
            }
        } catch (RuntimeException e) {
            log.warn("No se pudieron publicar los estados replicados; se reintenta ({})", e.getClass().getSimpleName());
        }
    }
}
