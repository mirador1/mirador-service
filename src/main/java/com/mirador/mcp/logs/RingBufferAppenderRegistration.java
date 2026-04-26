package com.mirador.mcp.logs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Programmatic Logback wiring : attaches the singleton
 * {@link LogbackRingBufferAppender} to the ROOT logger when the Spring
 * context finishes starting.
 *
 * <h3>Why programmatic, not declared in {@code logback-spring.xml} ?</h3>
 * <p>The XML file already declares the OTEL appender with experimental
 * attributes. Adding a third appender there means risking a typo that
 * breaks the existing OTEL pipeline (which is the production path, while
 * the ring buffer is only consumed locally by the MCP tool). Programmatic
 * registration via an {@link ApplicationListener} :
 * <ul>
 *   <li>keeps the XML file untouched ;</li>
 *   <li>ensures the appender bean exists ({@link LogbackRingBufferAppender}
 *       is a Spring bean defined in {@code McpConfig}) before the wiring
 *       happens ;</li>
 *   <li>is idempotent (we check for an existing attachment before adding).</li>
 * </ul>
 *
 * <p>Listening on {@link ApplicationStartedEvent} (not {@code Refreshed})
 * lets us miss the very first INFO lines emitted during context init, which
 * is fine — those are framework wiring messages, not domain events the LLM
 * cares about.
 */
@Component
public class RingBufferAppenderRegistration implements ApplicationListener<ApplicationStartedEvent> {

    /**
     * Stable name used to register the appender on the ROOT logger. Matches
     * the Spring bean name so the wiring is greppable from either side.
     */
    public static final String APPENDER_NAME = "MCP_RING_BUFFER";

    private final LogbackRingBufferAppender appender;

    /**
     * @param appender the buffer bean — Spring injects the same instance
     *                 that {@code LogsService} reads from, so the wiring
     *                 produces no double-buffering
     */
    public RingBufferAppenderRegistration(LogbackRingBufferAppender appender) {
        this.appender = appender;
    }

    /**
     * Attaches the buffer once the application context is started.
     *
     * @param event Spring's started signal — value is unused, only the
     *              timing matters
     */
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        // Guard : if the SLF4J binding isn't Logback (e.g. unit-tests using
        // a mock factory), skip attachment silently rather than crash. The
        // ring buffer becomes a no-op in that mode, which is acceptable.
        org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return;
        }

        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) != null) {
            // Idempotent : a hot-restart of the context (test reloads,
            // Spring DevTools) must not register the same appender twice
            // and double the captured events.
            return;
        }
        appender.setName(APPENDER_NAME);
        appender.setContext(context);
        if (!appender.isStarted()) {
            appender.start();
        }
        root.addAppender(appender);
    }
}
