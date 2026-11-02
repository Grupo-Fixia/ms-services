package com.fixia.services.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fixia.outbox.relay")
public class OutboxRelayProperties {

    /** Habilita o deshabilita la ejecución periódica del relay. */
    private boolean enabled = true;

    /** Intervalo de espera entre ejecuciones del relay en milisegundos. */
    private long fixedDelayMs = 2000;

    /** Retardo inicial antes de la primera ejecución del relay en milisegundos. */
    private long initialDelayMs = 5000;

    /** Tamaño del lote de eventos a seleccionar en cada ciclo. */
    private int batchSize = 50;

    /** Tiempo máximo de espera en segundos para el acuse de recibo de Kafka. */
    private long publishTimeoutSeconds = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getPublishTimeoutSeconds() {
        return publishTimeoutSeconds;
    }

    public void setPublishTimeoutSeconds(long publishTimeoutSeconds) {
        this.publishTimeoutSeconds = publishTimeoutSeconds;
    }
}

