package com.mirador.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TraceService}. Exercises both branches of the
 * {@link ScopedValue#orElse(Object)} path — bound vs. unbound scope — which
 * were jointly untested (the class was at 50 % method coverage).
 */
class TraceServiceTest {

    private final TraceService service = new TraceService();

    @Test
    void returnsDefault_whenNoRequestScopeIsActive() {
        // Outside any ScopedValue.where().run(), the ScopedValue is unbound
        // and orElse() must fall back to the documented default.
        assertThat(service.currentRequestIdOrDefault()).isEqualTo("no-request-id");
    }

    @Test
    void returnsBoundValue_whenRunningInsideScope() {
        ScopedValue.where(RequestContext.REQUEST_ID, "req-abc-123")
                .run(() -> assertThat(service.currentRequestIdOrDefault()).isEqualTo("req-abc-123"));
    }

    @Test
    void childScopeInheritsParentValue() {
        // Structured concurrency: nested scopes see the outer value when they don't override it.
        ScopedValue.where(RequestContext.REQUEST_ID, "outer").run(() -> {
            assertThat(service.currentRequestIdOrDefault()).isEqualTo("outer");
            ScopedValue.where(RequestContext.REQUEST_ID, "inner").run(() ->
                    assertThat(service.currentRequestIdOrDefault()).isEqualTo("inner"));
            // Back in the outer scope — the inner binding has unwound automatically.
            assertThat(service.currentRequestIdOrDefault()).isEqualTo("outer");
        });
    }
}
