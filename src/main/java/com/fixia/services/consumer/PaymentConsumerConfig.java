package com.fixia.services.consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Qué hace el consumidor de pagos (GC-490) cuando guardar el efecto falla, por ejemplo con la base caída: espera y
 * vuelve al mismo mensaje, sin límite de intentos y sin confirmar el offset, porque saltarlo dejaría a un servicio sin
 * poder iniciar aunque su pago esté retenido. Los mensajes inválidos no llegan aquí: el listener los salta.
 */
@Configuration
@ConditionalOnProperty(prefix = "fixia.payments.consumer", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PaymentConsumerConfig {

    @Bean
    public DefaultErrorHandler paymentConsumerErrorHandler(
            @Value("${fixia.payments.consumer.retry-delay-ms:5000}") long retryDelayMs) {
        return new DefaultErrorHandler(new FixedBackOff(retryDelayMs, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
