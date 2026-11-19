package com.fixia.services.service;

import com.fixia.services.domain.ServiceContract;
import com.fixia.services.dto.MessagePageResponse;
import com.fixia.services.exception.InvalidMessageException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Long polling del chat (GC-527, {@code docs/contracts/mensajeria.md} §2.3). Con {@code wait} la lectura no responde
 * vacía enseguida: la petición queda estacionada ({@link DeferredResult}, sin ocupar un hilo de Tomcat) y se vuelve a
 * mirar la base cada {@code fixia.chat.long-poll-interval} (1 s) hasta que llega un mensaje o se cumple la espera.
 *
 * <p>Se mira la base y no un aviso en memoria a propósito: hay varias réplicas de ms-services y el mensaje puede
 * guardarse en otra; todas leen la misma base, así que basta con preguntarle. Si el volumen crece, la revisión se puede
 * cambiar por {@code LISTEN/NOTIFY} de PostgreSQL sin tocar la API.
 */
@Component
public class MessageWaiter {

    public static final int MAX_WAIT_SECONDS = 25;

    private static final Logger log = LoggerFactory.getLogger(MessageWaiter.class);

    private final MessageService messages;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;

    public MessageWaiter(MessageService messages,
                         @Value("${fixia.chat.long-poll-interval:PT1S}") Duration interval,
                         @Value("${fixia.chat.long-poll-threads:2}") int threads) {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("fixia.chat.long-poll-interval debe ser una duración positiva");
        }
        this.messages = messages;
        this.interval = interval;
        this.scheduler = Executors.newScheduledThreadPool(Math.max(1, threads), runnable -> {
            Thread thread = new Thread(runnable, "chat-long-poll");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * La página de mensajes posteriores a {@code after}. Si ya hay alguno, o {@code waitSeconds} es 0 o nulo, responde
     * enseguida. Si no, espera hasta {@code waitSeconds} a que llegue uno; cumplido el plazo responde vacío con el mismo
     * cursor, para volver a preguntar desde ahí.
     *
     * @throws InvalidMessageException 400 si {@code waitSeconds} no está entre 0 y {@value #MAX_WAIT_SECONDS}, o si el
     *                                 cursor o el límite no son válidos (se comprueba antes de esperar)
     */
    public DeferredResult<MessagePageResponse> await(ServiceContract contract, String after, Integer limit,
                                                     Integer waitSeconds, UUID viewer) {
        int wait = waitSeconds == null ? 0 : waitSeconds;
        if (wait < 0 || wait > MAX_WAIT_SECONDS) {
            throw new InvalidMessageException("La espera (wait) debe estar entre 0 y " + MAX_WAIT_SECONDS + " segundos");
        }
        MessageService.Page first = messages.page(contract, after, limit);
        // Unos segundos de margen sobre la espera, para que el plazo lo marque esta clase y no el de Spring MVC.
        DeferredResult<MessagePageResponse> result = new DeferredResult<>((wait + 5) * 1000L);
        if (wait == 0 || !first.messages().isEmpty()) {
            result.setResult(MessagePageResponse.from(first, viewer));
            return result;
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(wait);
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
        Runnable check = () -> {
            if (result.isSetOrExpired()) {
                cancel(task);
                return;
            }
            try {
                MessageService.Page page = messages.page(contract, after, limit);
                if (!page.messages().isEmpty() || System.nanoTime() >= deadline) {
                    result.setResult(MessagePageResponse.from(page, viewer));
                    cancel(task);
                }
            } catch (RuntimeException e) {
                log.warn("Long polling del chat del contrato {} falló: {}", contract.getId(), e.toString());
                result.setErrorResult(e);
                cancel(task);
            }
        };
        long period = interval.toMillis();
        task.set(scheduler.scheduleWithFixedDelay(check, period, period, TimeUnit.MILLISECONDS));
        result.onCompletion(() -> cancel(task));
        result.onTimeout(() -> {
            cancel(task);
            result.setResult(MessagePageResponse.from(first, viewer));
        });
        result.onError(error -> cancel(task));
        return result;
    }

    private static void cancel(AtomicReference<ScheduledFuture<?>> task) {
        ScheduledFuture<?> future = task.get();
        if (future != null) {
            future.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
