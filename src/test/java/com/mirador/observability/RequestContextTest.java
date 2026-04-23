package com.mirador.observability;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RequestContext} — pin the ScopedValue (Java 21+
 * ThreadLocal replacement) wiring used to propagate the request ID
 * outside the filter chain.
 *
 * <p>Pinned contracts:
 *   - REQUEST_ID exists as a static, accessible from anywhere
 *   - Reading REQUEST_ID outside any bound scope throws (NoSuchElementException
 *     — ScopedValue's contract)
 *   - Reading REQUEST_ID INSIDE a {@code ScopedValue.where(...).run(...)} scope
 *     returns the bound value
 *   - Nested scopes can shadow the outer value (ScopedValue's stack semantics)
 */
class RequestContextTest {

    @Test
    void requestId_isAvailableAsStaticField() {
        // Pinned: callers do `RequestContext.REQUEST_ID.get()` — renaming
        // the field would break every consumer (TraceService, etc.). This
        // test pins the public API surface.
        assertThat(RequestContext.REQUEST_ID).isNotNull();
    }

    @Test
    void requestId_throwsWhenAccessedOutsideAnyScope() {
        // Pinned: ScopedValue's contract — reading without a binding scope
        // throws NoSuchElementException. Unlike ThreadLocal which would
        // return null silently, ScopedValue forces the caller to handle
        // the "no binding" case explicitly.
        assertThatThrownBy(() -> RequestContext.REQUEST_ID.get())
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void requestId_returnsBoundValueInsideWhereRunScope() {
        // Pinned: the canonical usage — RequestIdFilter wraps the request
        // chain in `ScopedValue.where(REQUEST_ID, "req-123").run(...)`.
        // Inside that scope, get() returns "req-123".
        AtomicReference<String> captured = new AtomicReference<>();

        java.lang.ScopedValue.where(RequestContext.REQUEST_ID, "req-abc-123")
                .run(() -> captured.set(RequestContext.REQUEST_ID.get()));

        assertThat(captured.get()).isEqualTo("req-abc-123");
    }

    @Test
    void requestId_isUnboundAfterScopeExits() {
        // Pinned: automatic cleanup is the whole point of ScopedValue
        // over ThreadLocal — no `try/finally { remove() }` boilerplate.
        // After the .run() returns, the binding is GONE.
        java.lang.ScopedValue.where(RequestContext.REQUEST_ID, "req-temporary")
                .run(() -> {
                    // Inside scope: bound
                    assertThat(RequestContext.REQUEST_ID.get()).isEqualTo("req-temporary");
                });

        // Outside scope: throws again (proof of automatic unbinding).
        assertThatThrownBy(() -> RequestContext.REQUEST_ID.get())
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void requestId_innerScopeShadowsOuterValue() {
        // Pinned: nested .where().run() shadows the outer binding ; the
        // outer value is restored after the inner scope exits. Same stack
        // semantics as nested try/finally with ThreadLocal but enforced
        // by the runtime instead of the developer.
        AtomicReference<String> innerValue = new AtomicReference<>();
        AtomicReference<String> outerAfterInner = new AtomicReference<>();

        java.lang.ScopedValue.where(RequestContext.REQUEST_ID, "outer-req")
                .run(() -> {
                    java.lang.ScopedValue.where(RequestContext.REQUEST_ID, "inner-req")
                            .run(() -> innerValue.set(RequestContext.REQUEST_ID.get()));
                    outerAfterInner.set(RequestContext.REQUEST_ID.get());
                });

        assertThat(innerValue.get()).isEqualTo("inner-req");
        assertThat(outerAfterInner.get()).isEqualTo("outer-req"); // outer restored
    }
}
