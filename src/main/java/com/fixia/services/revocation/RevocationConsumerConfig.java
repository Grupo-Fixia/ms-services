package com.fixia.services.revocation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;

/**
 * Contenedor propio del consumidor de revocaciones (GC-547), separado del de pagos: no confirma offsets (cada instancia
 * relee al arrancar), avisa cada {@code fixia.revocations.idle-interval-ms} sin mensajes para comprobar que está al día,
 * espera poco en cada poll() para que ese aviso llegue a tiempo, y lee desde el final si no hay marca de tiempo.
 */
@Configuration
@ConditionalOnProperty(prefix = "fixia.revocations.consumer", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class RevocationConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> revocationContainerFactory(
            KafkaProperties kafkaProperties,
            @Value("${fixia.revocations.idle-interval-ms:1000}") long idleIntervalMs) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        props.put("enable.auto.commit", false);
        props.put("auto.offset.reset", "latest");
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setIdleEventInterval(idleIntervalMs);
        // El aviso de inactividad solo se publica al volver de poll(), que por defecto espera hasta 5 s: con eso la lista
        // se confirmaba cada ~5 s y vencía (max-staleness 5 s). Con esperas cortas se confirma cada ~idle-interval.
        factory.getContainerProperties().setPollTimeout(Math.max(100, idleIntervalMs / 2));
        return factory;
    }
}
