package com.example.customerservice.observability;

/**
 * Holder for request-scoped values propagated via {@link ThreadLocal}.
 *
 * <p><b>Spring Boot 3 variant</b> — uses {@link InheritableThreadLocal} instead of
 * {@link java.lang.ScopedValue} which is a preview API in Java 21.
 * The SB4 version uses {@code ScopedValue} (finalised in Java 25).
 */
public final class RequestContext {

    private RequestContext() {
        // Utility class — not instantiable
    }

    /**
     * ThreadLocal carrying the current HTTP request ID.
     * Bound by {@link RequestIdFilter} at the start of each request.
     * Accessible anywhere within the same thread via {@code RequestContext.REQUEST_ID.get()}.
     *
     * <p>Uses {@link InheritableThreadLocal} so virtual-thread children inherit the value.
     */
    public static final InheritableThreadLocal<String> REQUEST_ID = new InheritableThreadLocal<>();
}
