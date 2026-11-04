package com.fixia.services.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Programador de tareas periódicas que activa el relay del Outbox a intervalos regulares (GC-434).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "fixia.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelayService relayService;
    private final OutboxRelayProperties properties;

    public OutboxRelayScheduler(OutboxRelayService relayService, OutboxRelayProperties properties) {
        this.relayService = relayService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${fixia.outbox.relay.fixed-delay-ms:2000}",
               initialDelayString = "${fixia.outbox.relay.initial-delay-ms:5000}")
    public void runRelay() {
        try {
            int processed = relayService.processBatch(properties.getBatchSize());
            if (processed > 0) {
                log.debug("Ciclo del relay finalizado. Procesados: {}", processed);
            }
        } catch (Exception e) {
            log.error("Error inesperado en la ejecución del ciclo de relay del Outbox: {}", e.getMessage(), e);
        }
    }
}

