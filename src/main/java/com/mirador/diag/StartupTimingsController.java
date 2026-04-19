package com.mirador.diag;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes the boot timings recorded by {@link StartupTimings}.
 *
 * <p>Endpoint: {@code GET /diag/startup-timings}. Returns JSON with:
 * <ul>
 *   <li>{@code totalBootMs} — JVM start → ApplicationReadyEvent.</li>
 *   <li>{@code readyAt} — ISO-8601 timestamp of the ready event.</li>
 *   <li>{@code beans} — insertion-ordered map of bean-name → init ms,
 *       filtered to beans that took ≥ 5 ms.</li>
 * </ul>
 *
 * <p>Not guarded by authentication on purpose: the data is purely
 * introspective and contains no secrets (bean names are already
 * exposed via {@code /actuator/beans}). Adding a JWT requirement
 * here would block the Angular dashboard from displaying it without
 * login, which is the main consumer.
 *
 * <p>Cache-Control: the values are immutable after ApplicationReadyEvent,
 * so cache freely on the client side.
 */
@Tag(name = "Diagnostic", description = "Runtime introspection endpoints")
@SecurityRequirements   // permit-all: no secrets, already visible in /actuator/beans
@RestController
@RequestMapping("/diag")
public class StartupTimingsController {

    private final StartupTimings timings;

    public StartupTimingsController(StartupTimings timings) {
        this.timings = timings;
    }

    @Operation(summary = "Startup timings",
            description = "Returns the total boot duration plus per-bean init cost (≥ 5 ms). "
                    + "Useful to explain what happened during the first few seconds "
                    + "of the Spring Boot startup — Flyway, Kafka listener registration, "
                    + "circuit-breaker wiring, OpenTelemetry agent, etc. Values are "
                    + "fixed once the ApplicationReadyEvent has fired.")
    @ApiResponse(responseCode = "200",
            description = "Timings snapshot. `totalBootMs: -1` until the ready event fires.")
    @GetMapping("/startup-timings")
    public ResponseEntity<Map<String, Object>> startupTimings() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalBootMs", timings.totalBootMs());
        body.put("readyAt", timings.readyAt() != null ? timings.readyAt().toString() : null);
        body.put("beans", timings.beanTimings());
        return ResponseEntity.ok(body);
    }
}
