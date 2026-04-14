package com.mirador.observability;

import com.mirador.observability.RequestContext;
import org.springframework.stereotype.Service;

/**
 * Exposes the current request ID from the {@link RequestContext} ScopedValue.
 *
 * <p>The request ID is set by {@link com.mirador.filter.RequestIdFilter}
 * at the beginning of each HTTP request (from the {@code X-Request-Id} header or generated
 * as a UUID) and stored in a {@link ScopedValue} bound to the current virtual thread.
 *
 * <p>This service provides a convenient accessor for components that need the request ID
 * outside of the filter chain — for example, in service-layer structured logs.
 * Using {@code orElse("no-request-id")} ensures a safe fallback for code paths that run
 * outside a request scope (e.g., scheduled tasks, Kafka listeners).
 */
@Service
public class TraceService {

    /**
     * Returns the request ID bound to the current thread scope, or {@code "no-request-id"}
     * if no request scope is active (e.g., called from a scheduled task or Kafka listener).
     */
    public String currentRequestIdOrDefault() {
        return RequestContext.REQUEST_ID.orElse("no-request-id");
    }
}
