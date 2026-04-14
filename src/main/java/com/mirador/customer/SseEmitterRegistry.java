package com.mirador.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry of active {@link SseEmitter} instances for the
 * {@code GET /customers/stream} endpoint.
 *
 * <p>Emitters are added when a client connects and removed automatically
 * when they complete, time out, or encounter an error.
 * A {@code ping} event is sent every 30 seconds to all active emitters to
 * keep the HTTP connections alive through proxies and load balancers.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    // CopyOnWriteArrayList: safe for concurrent reads from the scheduler and writes from request threads
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Creates a new {@link SseEmitter} with a 5-minute timeout, registers cleanup callbacks,
     * and adds it to the active emitter list.
     */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            emitters.remove(emitter);
        });
        emitter.onError(ex -> {
            emitter.completeWithError(ex);
            emitters.remove(emitter);
        });
        log.debug("sse_emitter_registered total={}", emitters.size());
        return emitter;
    }

    /**
     * Sends a typed SSE event to all active emitters.
     * Emitters that fail to receive the event are removed.
     */
    public void send(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
                emitters.remove(emitter);
            }
        }
    }

    /**
     * Sends a {@code ping} event every 30 seconds to keep connections alive.
     */
    @Scheduled(fixedDelay = 30_000)
    public void ping() {
        if (emitters.isEmpty()) {
            return;
        }
        log.debug("sse_ping active_emitters={}", emitters.size());
        send("ping", "{}");
    }
}
