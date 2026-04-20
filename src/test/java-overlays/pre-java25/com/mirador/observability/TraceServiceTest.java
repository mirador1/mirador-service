package com.mirador.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TraceService}.
 *
 * <p><b>Pre-Java-25 variant</b> — the production class uses {@link ScopedValue}
 * on SB4 + Java 25 but the compat overlay replaces it with {@link InheritableThreadLocal}.
 * This test exercises the same branches (bound vs. unbound) against the overlay.
 * {@code @AfterEach} clears the TLS to keep tests independent — ScopedValue unwinds
 * automatically, ThreadLocal does not.
 */
class TraceServiceTest {

    private final TraceService service = new TraceService();

    @AfterEach
    void clearContext() {
        RequestContext.REQUEST_ID.remove();
    }

    @Test
    void returnsDefault_whenNoRequestScopeIsActive() {
        assertThat(service.currentRequestIdOrDefault()).isEqualTo("no-request-id");
    }

    @Test
    void returnsBoundValue_whenRunningInsideScope() {
        RequestContext.REQUEST_ID.set("req-abc-123");
        assertThat(service.currentRequestIdOrDefault()).isEqualTo("req-abc-123");
    }

    @Test
    void childScopeInheritsParentValue() {
        RequestContext.REQUEST_ID.set("outer");
        assertThat(service.currentRequestIdOrDefault()).isEqualTo("outer");
        RequestContext.REQUEST_ID.set("inner");
        assertThat(service.currentRequestIdOrDefault()).isEqualTo("inner");
        RequestContext.REQUEST_ID.set("outer");
        assertThat(service.currentRequestIdOrDefault()).isEqualTo("outer");
    }
}
