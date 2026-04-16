package com.mirador.observability;

import org.springframework.stereotype.Service;

/**
 * Exposes the current request ID from the {@link RequestContext} ThreadLocal.
 *
 * <p><b>Spring Boot 3 variant</b> — uses {@link ThreadLocal#get()} with a null check
 * instead of {@link java.lang.ScopedValue#orElse(Object)} which is a preview API in Java 21.
 */
@Service
public class TraceService {

    /**
     * Returns the request ID bound to the current thread, or {@code "no-request-id"}
     * if no request scope is active (e.g., called from a scheduled task or Kafka listener).
     */
    public String currentRequestIdOrDefault() {
        String requestId = RequestContext.REQUEST_ID.get();
        return requestId != null ? requestId : "no-request-id";
    }
}
