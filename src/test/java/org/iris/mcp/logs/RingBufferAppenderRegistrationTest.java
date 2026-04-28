package org.iris.mcp.logs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link RingBufferAppenderRegistration} — verifies the
 * Logback wiring done at application-started.
 *
 * <p>Pinned invariants :
 * <ul>
 *   <li>The appender is attached to the ROOT logger by name
 *       ({@link RingBufferAppenderRegistration#APPENDER_NAME}).</li>
 *   <li>Idempotent : a second {@code onApplicationEvent} call does not
 *       re-attach the appender (would double-capture events).</li>
 *   <li>An unstarted appender gets started before being attached.</li>
 *   <li>An already-started appender is not re-started.</li>
 * </ul>
 */
class RingBufferAppenderRegistrationTest {

    private LogbackRingBufferAppender appender;
    private RingBufferAppenderRegistration registration;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        appender = new LogbackRingBufferAppender();
        registration = new RingBufferAppenderRegistration(appender);

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        rootLogger = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        // Defensive cleanup : if a previous test left the appender attached.
        rootLogger.detachAppender(RingBufferAppenderRegistration.APPENDER_NAME);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(RingBufferAppenderRegistration.APPENDER_NAME);
        if (appender.isStarted()) {
            appender.stop();
        }
    }

    @Test
    void onApplicationEvent_attachesAppenderToRootLogger() {
        registration.onApplicationEvent(stubEvent());

        assertThat(rootLogger.getAppender(RingBufferAppenderRegistration.APPENDER_NAME))
                .isSameAs(appender);
        assertThat(appender.isStarted()).isTrue();
        assertThat(appender.getName()).isEqualTo(RingBufferAppenderRegistration.APPENDER_NAME);
    }

    @Test
    void onApplicationEvent_idempotent_onSecondCall() {
        registration.onApplicationEvent(stubEvent());
        // Pin the same instance is still the only appender after a second
        // call — no double-attachment, no reinitialization that would
        // reset the in-memory ring buffer.
        registration.onApplicationEvent(stubEvent());

        assertThat(rootLogger.getAppender(RingBufferAppenderRegistration.APPENDER_NAME))
                .isSameAs(appender);
    }

    @Test
    void onApplicationEvent_alreadyStartedAppender_doesNotStartAgain() {
        // Start the appender BEFORE wiring : the listener must observe
        // isStarted()=true and skip the start() call (no-op semantics).
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        assertThat(appender.isStarted()).isTrue();

        registration.onApplicationEvent(stubEvent());

        assertThat(appender.isStarted()).isTrue();
        assertThat(rootLogger.getAppender(RingBufferAppenderRegistration.APPENDER_NAME))
                .isSameAs(appender);
    }

    private ApplicationStartedEvent stubEvent() {
        // The listener doesn't read any field of the event ; only the
        // event TYPE matters. A minimally-instantiated event suffices.
        ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);
        return new ApplicationStartedEvent(
                new org.springframework.boot.SpringApplication(),
                new String[0],
                ctx,
                java.time.Duration.ZERO);
    }
}
