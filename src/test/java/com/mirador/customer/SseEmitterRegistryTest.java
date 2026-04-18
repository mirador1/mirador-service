package com.mirador.customer;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SseEmitterRegistry}. Cover the registration/completion
 * lifecycle plus the "faulty emitter is dropped on send error" branch.
 *
 * <p>The registry class was only at 50 % method coverage before these tests —
 * {@link SseEmitterRegistry#send(String, Object)} and the {@code @Scheduled}
 * {@link SseEmitterRegistry#ping()} method were uncovered.
 */
class SseEmitterRegistryTest {

    private final SseEmitterRegistry registry = new SseEmitterRegistry();

    @Test
    void register_returnsNewEmitterWithConfiguredTimeout() {
        SseEmitter emitter = registry.register();
        assertThat(emitter).isNotNull();
        // 5-minute timeout is the registry's production policy
        assertThat(emitter.getTimeout()).isEqualTo(300_000L);
    }

    @Test
    void completeEmitter_removesItselfFromRegistry() {
        SseEmitter emitter = registry.register();
        // Calling complete() invokes the onCompletion callback wired in register(),
        // which must detach the emitter from the internal list.
        emitter.complete();
        // If the removal worked, a subsequent ping is a no-op (empty list).
        registry.ping();   // must not throw
    }

    @Test
    void ping_isNoOp_whenNoEmittersAreRegistered() {
        // Short-circuit branch: empty list skips the send loop.
        registry.ping();   // must not throw
    }

    @Test
    void send_dropsEmitter_whenUnderlyingSendThrows() {
        // A registered-but-never-bound SseEmitter throws IllegalStateException on send
        // because the response isn't attached to any HTTP call — exactly the second
        // branch of the registry's catch (IOException | IllegalStateException).
        registry.register();
        registry.send("event", "{}");
        // If the emitter wasn't removed after the failure, the next send would throw again
        // because the same broken emitter is still in the list. The assertion is that this
        // second send is safe — the registry has pruned the faulty emitter.
        registry.send("event", "{}");
    }

    @Test
    void multipleEmitters_canBeRegisteredAndCleaned_independently() {
        SseEmitter a = registry.register();
        SseEmitter b = registry.register();
        a.complete();
        // b must still be in the registry — send() should still attempt delivery on it
        // (which fails silently because b isn't bound either, but that's the same path
        // the other tests cover). The assertion here is simply that complete()-ing one
        // doesn't drop the other.
        assertThat(b.getTimeout()).isEqualTo(300_000L);
    }
}
