package com.fixia.services.revocation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lista local de access tokens revocados (GC-547), alimentada por {@code sesion.revocada} de ms-users. Es la única
 * fuente de {@code TokenRevocationValidator}: ms-services ya no pregunta a ms-users.
 *
 * <p>Solo sirve si está al día. El consumidor marca {@link #markCaughtUp()} cuando confirma con el broker que leyó
 * todo lo publicado; si la última confirmación tiene más de {@code fixia.revocations.max-staleness} (5 s por defecto),
 * la lista no se usa y la petición falla cerrada, como antes de GC-547. Con el consumidor apagado nunca está al día.
 *
 * <p>Vive en memoria: cada instancia la reconstruye al arrancar releyendo los últimos minutos del tópico. Cada
 * {@code jti} se guarda hasta su vencimiento y se olvida después.
 */
@Component
public class RevocationList {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration maxStaleness;
    private volatile Instant caughtUpAt;

    public RevocationList(Clock clock,
                          @Value("${fixia.revocations.max-staleness:PT5S}") Duration maxStaleness) {
        this.clock = clock;
        this.maxStaleness = maxStaleness;
    }

    /** Recuerda un token revocado hasta su vencimiento; uno ya vencido no hace falta guardarlo. */
    public void add(String jti, Instant expiresAt) {
        Instant now = clock.instant();
        if (expiresAt.isAfter(now)) {
            revoked.put(jti, expiresAt);
        }
        revoked.values().removeIf(expiry -> !expiry.isAfter(now));
    }

    public boolean contains(String jti) {
        Instant expiry = revoked.get(jti);
        return expiry != null && expiry.isAfter(clock.instant());
    }

    /** El consumidor confirmó con el broker que no le falta nada por leer. */
    public void markCaughtUp() {
        caughtUpAt = clock.instant();
    }

    /** ¿Se puede decidir con la lista? Solo si la última confirmación es reciente. */
    public boolean isFresh() {
        Instant last = caughtUpAt;
        return last != null && !last.plus(maxStaleness).isBefore(clock.instant());
    }

    int size() {
        return revoked.size();
    }
}
