package com.example.customerservice.observability;

import io.opentelemetry.exporter.zipkin.ZipkinSpanExporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers a {@link ZipkinSpanExporter} so traces are sent to both
 * OTLP (Tempo) and Zipkin in parallel.
 *
 * <p>The OTel SDK supports multiple span exporters: when this bean is present
 * alongside the OTLP exporter auto-configured by Spring Boot, both receive
 * every completed span.
 *
 * <p>Endpoint is configurable via {@code management.zipkin.endpoint}
 * (default: {@code http://localhost:9411/api/v2/spans}).
 */
@Configuration
public class ZipkinExporterConfig {

    @Bean
    ZipkinSpanExporter zipkinSpanExporter(
            @Value("${management.zipkin.endpoint:http://localhost:9411/api/v2/spans}") String endpoint) {
        return ZipkinSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();
    }
}
