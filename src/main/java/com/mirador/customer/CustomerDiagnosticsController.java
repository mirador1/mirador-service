package com.mirador.customer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Diagnostic / observability-demo endpoints for the {@code /customers} resource —
 * extracted from {@link CustomerController} 2026-04-22 under Phase B-7-7
 * (file-length hygiene). These three endpoints share a "stream / one-shot
 * unbounded payload / synthetic latency" theme that has nothing to do with
 * the customer CRUD lifecycle:
 *
 * <ul>
 *   <li>{@code GET /customers/stream} — Server-Sent Events fan-out for new
 *       customer creations. Used by the UI dashboard real-time feed.</li>
 *   <li>{@code GET /customers/slow-query} — {@code SELECT pg_sleep(N)}
 *       latency injector. Lets observability demos show a long DB span in
 *       Grafana Tempo without requiring a real slow query.</li>
 *   <li>{@code GET /customers/export} — full-table CSV streamed via
 *       {@link StreamingResponseBody} (no in-memory list).</li>
 * </ul>
 *
 * <p>Co-located on {@code /customers} via {@link RequestMapping} — Spring
 * tolerates multiple controllers mapping the same base path.
 *
 * <p>Why a separate controller instead of inline: the 3 endpoints depend on
 * different collaborators ({@link SseEmitterRegistry} for stream,
 * {@link CustomerService#findAllForExport()} for CSV, {@link
 * CustomerService#simulateSlowQuery(double)} for pg_sleep) than the rest of
 * {@link CustomerController}, and they don't use the {@code observe()}
 * Observation helper (no metrics worth a tag — these are demo/diag).
 * Splitting cuts {@link CustomerController} from 782 → ~680 LOC and makes
 * each file have a single responsibility.
 */
@Tag(name = "Customers — diagnostics",
     description = "Real-time stream, slow-query simulator, and CSV export — observability demo endpoints, not core CRUD.")
@RestController
@RequestMapping(CustomerController.PATH_CUSTOMERS)
public class CustomerDiagnosticsController {

    private final CustomerService service;
    private final SseEmitterRegistry sseEmitterRegistry;

    public CustomerDiagnosticsController(CustomerService service,
                                         SseEmitterRegistry sseEmitterRegistry) {
        this.service = service;
        this.sseEmitterRegistry = sseEmitterRegistry;
    }

    /**
     * Server-Sent Events stream — pushes a {@code customer} event each time
     * a new customer is created (via {@link SseEmitterRegistry}). A {@code ping}
     * event every 30 s keeps the connection alive across HTTP/1.1 idle timeouts.
     *
     * <p>{@link SecurityRequirements} is empty: {@code EventSource} cannot send
     * custom headers (no {@code Authorization: Bearer …}), so this endpoint is
     * declared {@code permitAll} in Spring Security config.
     */
    @Operation(summary = "Server-Sent Events stream",
            description = "Opens an SSE connection that pushes `customer` events (JSON) whenever a new customer is created. "
                    + "A `ping` event is sent every 30 s to keep the connection alive. "
                    + "**No authentication required** — `EventSource` cannot send custom headers, so this endpoint is `permitAll`.")
    @ApiResponse(responseCode = "200", description = "SSE stream — content-type: text/event-stream")
    @SecurityRequirements
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return sseEmitterRegistry.register();
    }

    /**
     * Simulates a slow database query by running {@code SELECT pg_sleep(N)}
     * inside the service layer. The resulting DB span is visible in Grafana
     * Tempo as a long-duration child of the HTTP span — a cleaner demo than
     * waiting for a real slow query to surface.
     *
     * @param seconds duration of the simulated slow query (capped at 10s
     *                so a misuse can't tie up a connection indefinitely)
     */
    @Operation(summary = "Simulate a slow database query",
            description = "Runs `SELECT pg_sleep(N)` to inject artificial latency. "
                    + "The resulting DB span is visible in distributed traces (Grafana Tempo). "
                    + "Max 10 seconds.")
    @ApiResponse(responseCode = "200", description = "Query completed — returns `{status, duration}`")
    @GetMapping("/slow-query")
    public Map<String, String> slowQuery(
            @Parameter(description = "Sleep duration in seconds (capped at 10)", example = "2")
            @RequestParam(defaultValue = "2") double seconds) {
        double capped = Math.min(seconds, 10);
        service.simulateSlowQuery(capped);
        return Map.of("status", "completed", "duration", capped + "s");
    }

    /**
     * Triggers a deliberate database failure for chaos demos. Runs an
     * intentionally-bad SQL statement (SELECT from a non-existent table)
     * — Postgres rejects it, the framework maps the resulting
     * DataAccessException to a 500 ProblemDetail. The dedicated URI
     * lets the SLO dashboards annotate "db-failure" distinctly from
     * "slow-query" or "kafka-timeout" via Prometheus
     * {@code uri="/customers/db-failure"} filter.
     *
     * <p>Why a separate endpoint when the MCP {@code trigger_chaos_experiment}
     * tool already does this : Prometheus annotations need a stable HTTP
     * URI to filter on. The MCP tool calls go through {@code /mcp}, not
     * the chaos URL, so they're invisible to the SLO dashboards. The
     * dedicated endpoint gives the dashboards a distinct burn signal.
     */
    @Operation(summary = "Simulate a deliberate DB failure",
            description = "Runs intentionally-bad SQL (SELECT from a non-existent table) — Postgres rejects it, "
                    + "the framework returns a 500 ProblemDetail. Use this to exercise the availability SLO burn-rate "
                    + "alert via a distinct `uri=/customers/db-failure` label.")
    @ApiResponse(responseCode = "500", description = "DB rejected the bad SQL — ProblemDetail body.")
    @PostMapping("/db-failure")
    public Map<String, String> dbFailure() {
        service.simulateDbFailure();
        // Unreachable — simulateDbFailure always throws — but the
        // method needs a return for the compiler.
        return Map.of("scenario", "db-failure", "synthetic", "false");
    }

    /**
     * Triggers a synthetic Kafka-timeout response. Returns 504 with a
     * marker body — no actual broker call is made. The dedicated URI
     * lets the SLO dashboards annotate "kafka-timeout" distinctly from
     * the real {@code /customers/{id}/enrich} 504 path (which is on
     * the enrichment SLO).
     *
     * <p>Mirrors the Python sibling's {@code _trigger_chaos_experiment}
     * synthetic 504 — same shape so the demo behaves identically across
     * backends.
     */
    @Operation(summary = "Simulate a Kafka timeout",
            description = "Returns 504 with a synthetic marker body — no real broker call. Distinct "
                    + "`uri=/customers/kafka-timeout` label for SLO annotation. Mirrors the synthetic "
                    + "504 shape from the MCP `trigger_chaos_experiment` tool.")
    @ApiResponse(responseCode = "504", description = "Synthetic timeout — no broker called.")
    @PostMapping("/kafka-timeout")
    public ResponseEntity<Map<String, String>> kafkaTimeout() {
        Map<String, String> body = service.simulateKafkaTimeout();
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
    }

    /**
     * Streams the full customer table as CSV directly to the response output
     * stream — uses {@link StreamingResponseBody} to avoid materialising the
     * entire result set in memory (a 50 K-row export would otherwise trigger
     * GC pressure in the request thread).
     */
    @Operation(summary = "Export all customers as CSV",
            description = "Streams all customers directly to the response output stream using `StreamingResponseBody` — "
                    + "avoids loading the entire result set into memory. "
                    + "Returns `Content-Disposition: attachment; filename=customers.csv`.")
    @ApiResponse(responseCode = "200", description = "CSV file stream",
            content = @Content(mediaType = "text/csv"))
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> exportCsv() {
        StreamingResponseBody body = outputStream -> {
            var writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            writer.println("id,name,email,created_at");
            for (Customer c : service.findAllForExport()) {
                writer.printf("%d,\"%s\",\"%s\",%s%n",
                        c.getId(),
                        c.getName().replace("\"", "\"\""),
                        c.getEmail().replace("\"", "\"\""),
                        c.getCreatedAt());
            }
            writer.flush();
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=customers.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }
}
