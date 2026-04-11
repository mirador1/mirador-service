package com.example.springapi.context;

/**
 * Holder for request-scoped values propagated via Java 21+ {@link ScopedValue}.
 *
 * <h3>What is a ScopedValue?</h3>
 * <p>{@link ScopedValue} (JEP 446, finalised in Java 21) is the modern replacement for
 * {@link ThreadLocal} in virtual-thread and structured-concurrency contexts:
 * <ul>
 *   <li>Values are <em>immutable</em> within a scope — no accidental overwrites.</li>
 *   <li>Values are <em>automatically unbound</em> when the scope exits — no cleanup needed.</li>
 *   <li>Values are inherited by child scopes (structured concurrency), unlike ThreadLocal
 *       which requires explicit {@code InheritableThreadLocal}.</li>
 * </ul>
 *
 * <h3>Usage in this project</h3>
 * <p>{@link #REQUEST_ID} is bound by {@link com.example.springapi.filter.RequestIdFilter}
 * for each incoming HTTP request and consumed by {@link com.example.springapi.service.TraceService}
 * wherever the request ID is needed outside the filter chain.
 *
 * <p>Note: The filter currently uses SLF4J {@code MDC} as the primary propagation mechanism
 * (for log enrichment). {@code REQUEST_ID} here provides an alternative thread-safe accessor
 * for programmatic use without depending on the MDC.
 */
public final class RequestContext {

    private RequestContext() {
        // Utility class — not instantiable
    }

    /**
     * ScopedValue carrying the current HTTP request ID.
     * Bound by {@link com.example.springapi.filter.RequestIdFilter} at the start of each request.
     * Accessible anywhere within the same scope via {@code RequestContext.REQUEST_ID.get()}.
     */
    public static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance(); // [Java 21+] ScopedValue replaces ThreadLocal
}
