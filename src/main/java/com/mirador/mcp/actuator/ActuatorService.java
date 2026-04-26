package com.mirador.mcp.actuator;

import com.mirador.mcp.dto.EnvSnapshot;
import com.mirador.mcp.dto.HealthSnapshot;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MCP tool surface for Spring Boot Actuator queries.
 *
 * <p>Wraps the in-process {@code HealthEndpoint}, {@code InfoEndpoint}, and
 * Spring {@code Environment} directly — NO HTTP self-call (which would
 * defeat the purpose and bypass Spring Security on /actuator). Per ADR-0062
 * the backend stays infrastructure-agnostic ; the Actuator endpoints are
 * already part of every Spring Boot app, so consuming them from inside the
 * JVM is the cleanest contract.
 *
 * <h3>Why a service rather than a controller wrapper ?</h3>
 * <p>The {@code @Tool} annotations live on the service layer per ADR-0062.
 * If a future change moves to a different MCP wiring, the tool methods
 * stay where they are.
 *
 * <h3>Env redaction</h3>
 * <p>Property values whose name matches the case-insensitive pattern
 * {@code .*(password|secret|token|key|credential).*} are replaced by
 * {@code "***"} BEFORE the snapshot is built — see {@link #SECRET_NAME_PATTERN}.
 * The redaction happens at the MCP boundary, not relying on the
 * sanitization of the underlying actuator (which can be reconfigured).
 */
@Service
public class ActuatorService {

    /**
     * Pattern matched against property names — case-insensitive. Reject
     * anything carrying a credential-like substring. Wider than the
     * default Actuator sanitizer to err on the side of caution for an
     * LLM-facing surface.
     */
    static final Pattern SECRET_NAME_PATTERN = Pattern.compile(
            "(?i).*(password|secret|token|key|credential).*");

    /** Standard redaction marker — three asterisks per Spring convention. */
    static final String REDACTED = "***";

    /**
     * Hard cap on env properties returned per call. Without it, a regex
     * matching everything would dump the whole environment (~3000 entries
     * on a Spring Boot 4 app) into the LLM's window.
     */
    public static final int MAX_ENV_PROPERTIES = 200;

    private final HealthEndpoint healthEndpoint;
    private final InfoEndpoint infoEndpoint;
    private final EnvironmentSnapshotProvider envProvider;

    /**
     * Production constructor — Spring auto-wires everything.
     *
     * <p>Both Actuator beans are wrapped in {@link ObjectProvider} so the
     * service still loads when the endpoint is disabled
     * ({@code management.endpoint.<x>.access=none}) or excluded from
     * {@code management.endpoints.web.exposure.include}. Calls to a tool
     * that needs an absent endpoint return an explicit "endpoint
     * unavailable" message rather than crash the whole MCP server.
     *
     * @param healthEndpointProvider composite health bean — optional
     * @param infoEndpointProvider   info contributors bean — optional
     * @param envProvider            indirection over the Spring {@link
     *                               org.springframework.core.env.Environment}
     */
    @Autowired
    public ActuatorService(ObjectProvider<HealthEndpoint> healthEndpointProvider,
                           ObjectProvider<InfoEndpoint> infoEndpointProvider,
                           EnvironmentSnapshotProvider envProvider) {
        this.healthEndpoint = healthEndpointProvider.getIfAvailable();
        this.infoEndpoint = infoEndpointProvider.getIfAvailable();
        this.envProvider = envProvider;
    }

    /**
     * Test-only constructor that takes already-resolved dependencies. Lets
     * unit tests pass mocks without going through {@link ObjectProvider}.
     */
    ActuatorService(HealthEndpoint healthEndpoint,
                    InfoEndpoint infoEndpoint,
                    EnvironmentSnapshotProvider envProvider) {
        this.healthEndpoint = healthEndpoint;
        this.infoEndpoint = infoEndpoint;
        this.envProvider = envProvider;
    }

    /**
     * Returns the composite health status WITHOUT per-indicator details —
     * safe for any authenticated role.
     *
     * @return the snapshot, never {@code null}
     */
    @Tool(name = "get_health",
            description = "Returns the composite health status of the backend (UP / DOWN / "
                    + "OUT_OF_SERVICE / UNKNOWN) plus per-indicator status (db, kafka, "
                    + "redis…) without sensitive details. Backed by the Spring Boot "
                    + "HealthEndpoint bean — NO HTTP self-call. Safe for any role.")
    public HealthSnapshot getHealth() {
        if (healthEndpoint == null) {
            return new HealthSnapshot("UNKNOWN", java.util.Map.of());
        }
        HealthDescriptor descriptor = healthEndpoint.health();
        return toSnapshot(descriptor, false);
    }

    /**
     * Returns the composite health WITH per-indicator details (DB validation
     * query, Kafka broker, Redis ping latency, …). Admin-gated because
     * details can leak deployment info (driver versions, broker URLs, …).
     *
     * <p>Because Spring Boot 4's {@link HealthEndpoint#health()} already
     * surfaces a fully-populated descriptor (the {@code show-details} knob
     * is honoured upstream), this method walks the same descriptor tree
     * but extracts the {@code details} map of each leaf indicator.
     *
     * @return the detailed snapshot
     */
    @Tool(name = "get_health_detail",
            description = "Returns the composite health WITH per-indicator details (db "
                    + "validation query, kafka broker, redis ping latency, …). Admin "
                    + "only — details can leak driver versions / broker URLs.")
    @PreAuthorize("hasRole('ADMIN')")
    public HealthSnapshot getHealthDetail() {
        if (healthEndpoint == null) {
            return new HealthSnapshot("UNKNOWN", java.util.Map.of());
        }
        HealthDescriptor descriptor = healthEndpoint.health();
        return toSnapshot(descriptor, true);
    }

    /**
     * Returns Spring environment properties matching the supplied prefix,
     * with secrets redacted.
     *
     * @param prefix property name prefix ({@code spring.}, {@code mirador.},
     *               …) ; {@code null} or blank means "all properties" ;
     *               capped at {@link #MAX_ENV_PROPERTIES} entries
     */
    @Tool(name = "get_actuator_env",
            description = "Returns Spring environment properties starting with the given "
                    + "prefix. Secrets are auto-redacted (any property name matching "
                    + "password|secret|token|key|credential returns ***). Capped at "
                    + "200 entries.")
    public EnvSnapshot getEnv(
            @ToolParam(required = false, description = "Property name prefix (e.g. spring.datasource. , "
                    + "mirador.). Omit for all properties.")
            String prefix
    ) {
        Map<String, Object> raw = envProvider.snapshot(prefix);
        Map<String, String> redacted = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (count >= MAX_ENV_PROPERTIES) {
                break;
            }
            String value = SECRET_NAME_PATTERN.matcher(e.getKey()).matches()
                    ? REDACTED
                    : String.valueOf(e.getValue());
            redacted.put(e.getKey(), value);
            count++;
        }
        return new EnvSnapshot(Map.copyOf(redacted));
    }

    /**
     * Returns the {@code /actuator/info} payload — build, git, version,
     * etc. depending on the configured info contributors. Safe for any
     * role ; the contributors are already filtered to safe values upstream.
     *
     * @return contributor key → contributed value map
     */
    @Tool(name = "get_actuator_info",
            description = "Returns the actuator info payload — build version, git SHA, "
                    + "version, contributors. Useful to confirm which build is running. "
                    + "Safe for any role.")
    public Map<String, Object> getInfo() {
        if (infoEndpoint == null) {
            return Map.of();
        }
        Map<String, Object> info = infoEndpoint.info();
        return info == null ? Map.of() : Map.copyOf(info);
    }

    /**
     * Adapts a Spring Boot 4 {@link HealthDescriptor} (root of the health
     * tree) to the immutable DTO. Walks composite descriptors recursively
     * to surface each named indicator.
     */
    private HealthSnapshot toSnapshot(HealthDescriptor descriptor, boolean withDetails) {
        String status = descriptor.getStatus().getCode();
        Map<String, HealthSnapshot.ComponentStatus> components = new HashMap<>();
        if (descriptor instanceof CompositeHealthDescriptor composite) {
            for (Map.Entry<String, HealthDescriptor> e : composite.getComponents().entrySet()) {
                components.put(e.getKey(), toComponent(e.getValue(), withDetails));
            }
        }
        return new HealthSnapshot(status, Map.copyOf(components));
    }

    /**
     * Adapts a single child indicator. Details are only carried over when
     * the caller is admin-authorised AND the indicator publishes them.
     */
    private HealthSnapshot.ComponentStatus toComponent(HealthDescriptor descriptor, boolean withDetails) {
        String status = descriptor.getStatus().getCode();
        Map<String, Object> details = Map.of();
        if (withDetails && descriptor instanceof IndicatedHealthDescriptor indicated
                && indicated.getDetails() != null) {
            details = Map.copyOf(indicated.getDetails());
        }
        return new HealthSnapshot.ComponentStatus(status, details);
    }
}
