package com.mirador.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Installs the OpenTelemetry Logback appender after the SDK is initialized.
 *
 * <p>The {@link OpenTelemetryAppender} in logback-spring.xml captures log events
 * but needs the OpenTelemetry SDK instance to export them. The SDK is created by
 * Spring Boot auto-configuration, so we install it once the application is ready.
 */
@Component
public class OtelLogbackInstaller {

    private final OpenTelemetry openTelemetry;

    public OtelLogbackInstaller(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void install() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
