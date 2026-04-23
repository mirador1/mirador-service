package com.mirador.observability;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

/**
 * Unit test for {@link OtelLogbackInstaller} — pin the
 * ApplicationReady listener that wires OpenTelemetry into the Logback
 * appender configured in {@code logback-spring.xml}.
 *
 * <p>Pinned contract :
 *   - install() doesn't throw — the appender accepts any
 *     {@link OpenTelemetry} instance (real or mock). A regression that
 *     unguarded null OR threw on noop OTel would crash app startup.
 *
 * <p>The actual side-effect (registering the OTel instance with the
 * appender) is verified by the integration tests that rely on log
 * forwarding to Tempo. Here we only pin the boot-safety contract.
 */
class OtelLogbackInstallerTest {

    @Test
    void install_doesNotThrow_withMockOpenTelemetryInstance() {
        // Pinned: install() runs at ApplicationReadyEvent — if it
        // threw, the application would partially start (context loaded)
        // then crash on the listener. Spring would log the failure but
        // the JVM would stay up in a broken state. Defensive : no
        // exception propagation from the listener is the right contract.
        OpenTelemetry otel = mock(OpenTelemetry.class);
        OtelLogbackInstaller installer = new OtelLogbackInstaller(otel);

        assertThatNoException().isThrownBy(() ->
                installer.install());
    }

    @Test
    void install_isIdempotent_acrossMultipleInvocations() {
        // Defensive : if for any reason ApplicationReadyEvent fires
        // twice (test context refresh, etc.), the installer must
        // tolerate repeat calls. The OpenTelemetryAppender.install()
        // upstream method is safe to call multiple times.
        OpenTelemetry otel = mock(OpenTelemetry.class);
        OtelLogbackInstaller installer = new OtelLogbackInstaller(otel);

        installer.install();
        installer.install();
        installer.install();

        // No exception expected
    }
}
