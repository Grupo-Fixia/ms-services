package com.fixia.services.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class AppConfig {

    /**
     * Reloj del servicio con precisión de microsegundos, la misma que guardan PostgreSQL y H2. Así un instante del
     * dominio es idéntico al que queda en la base y al que viaja en el evento del Outbox (GC-433); con el reloj del
     * sistema (100 ns en Windows) la base lo redondea y los tres dejan de coincidir.
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
