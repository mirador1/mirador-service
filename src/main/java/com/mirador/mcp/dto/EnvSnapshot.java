package com.mirador.mcp.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Filtered Spring Environment snapshot surfaced by {@code get_actuator_env}.
 *
 * <p>Wraps Spring Boot Actuator's in-process {@code EnvironmentEndpoint}.
 * Property values matching {@code (?i).*(password|secret|token|key|credential).*}
 * are replaced by {@code "***"} BEFORE the DTO is built — see
 * {@code com.mirador.mcp.actuator.ActuatorService#getEnv}. The redaction
 * is applied at the service boundary, not the appender, so audit logs
 * also benefit.
 *
 * @param properties property name → (possibly redacted) string value
 */
@Schema(description = "Filtered Spring Environment properties — secrets are redacted")
public record EnvSnapshot(
        Map<String, String> properties
) {
}
