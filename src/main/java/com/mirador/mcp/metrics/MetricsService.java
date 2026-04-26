package com.mirador.mcp.metrics;

import com.mirador.mcp.dto.MetricSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP tool surface for in-process Micrometer registry queries.
 *
 * <p>Backed by the {@link MeterRegistry} bean — NO Mimir / Prometheus HTTP
 * call. Per ADR-0062 the Spring Boot jar must stay infrastructure-agnostic ;
 * external metric backends are SEPARATE community MCP servers.
 *
 * <p>Results are cached via Caffeine for {@link #CACHE_NAME} for 5 seconds —
 * an LLM often issues the same query twice in a row while reasoning, no
 * point reading the registry twice in that window. The TTL is configured
 * in {@code application.yml} to keep timing knobs co-located with the
 * other caches.
 */
@Service
public class MetricsService {

    /** Caffeine cache name — declared in {@code application.yml}. */
    public static final String CACHE_NAME = "mcp-metrics";

    /**
     * Hard ceiling on the number of metric snapshots returned in a single
     * call. Defends against a curious LLM asking for "all of them" on a
     * registry with thousands of series.
     */
    public static final int MAX_RESULTS = 200;

    private final MeterRegistry registry;

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns Micrometer meter samples filtered by name regex and tag
     * predicates. Reading from the in-process registry is O(N) where N is
     * the meter count ; the {@link #MAX_RESULTS} ceiling keeps even a
     * "match-all" query bounded.
     *
     * @param nameFilter regex matched against {@code Meter.Id.name} ;
     *                   {@code null} or blank means "no name filter".
     *                   Java {@link Pattern} syntax — the LLM should pass
     *                   simple anchors like {@code ^http\.server\.requests$}
     *                   rather than free-form prose.
     * @param tagsFilter exact-match predicates on tag values ;
     *                   {@code null} or empty means "no tag filter". A meter
     *                   matches when ALL supplied tags are present with the
     *                   exact value.
     * @return at most {@link #MAX_RESULTS} snapshots, ordered by meter name.
     */
    @Tool(name = "get_metrics",
            description = "Returns Micrometer meter samples (counters, gauges, timers) by "
                    + "name regex + tag filter. Backed by the in-process MeterRegistry — "
                    + "NO Mimir/Prometheus call. Use to check the live value of "
                    + "http.server.requests, jvm.memory.used, customer.created, etc. "
                    + "Cached for 5s to avoid double reads.")
    @Cacheable(cacheNames = CACHE_NAME, sync = true)
    public List<MetricSnapshot> getMetrics(
            @ToolParam(required = false, description = "Java regex to match meter names. "
                    + "Examples : '^http\\.server\\.requests$' for HTTP timer alone, "
                    + "'^jvm\\.memory\\..*' for the JVM memory family. Omit for all "
                    + "meters (capped at 200 results).")
            String nameFilter,
            @ToolParam(required = false, description = "Tag filters as a flat map (key → "
                    + "exact value). A meter matches when every entry is present and "
                    + "equal. Use to scope http.server.requests to a single URI.")
            Map<String, String> tagsFilter
    ) {
        Pattern compiled = compileSafe(nameFilter);
        Map<String, String> tags = tagsFilter == null ? Map.of() : tagsFilter;
        Instant now = Instant.now();

        return registry.getMeters().stream()
                .filter(m -> compiled == null || compiled.matcher(m.getId().getName()).find())
                .filter(m -> matchesAllTags(m, tags))
                .sorted((a, b) -> a.getId().getName().compareTo(b.getId().getName()))
                .limit(MAX_RESULTS)
                .map(m -> toSnapshot(m, now))
                .collect(Collectors.toList());
    }

    /**
     * Compiles the user-supplied regex defensively. A null / blank string
     * means "no filter". An invalid regex returns {@code null} (also "no
     * filter") rather than crash the tool call — an LLM that types a bad
     * pattern shouldn't take down the request.
     */
    private Pattern compileSafe(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(filter);
        } catch (java.util.regex.PatternSyntaxException ex) {
            // Defensive : invalid regex from the LLM falls back to "match
            // all" rather than throw — the alternative is a confusing
            // 500 the model can't parse.
            return null;
        }
    }

    /**
     * Returns true when the meter carries every requested tag with the
     * exact requested value. An empty filter map matches anything.
     */
    private boolean matchesAllTags(Meter meter, Map<String, String> requested) {
        if (requested.isEmpty()) {
            return true;
        }
        Map<String, String> meterTags = new HashMap<>();
        for (Tag t : meter.getId().getTagsAsIterable()) {
            meterTags.put(t.getKey(), t.getValue());
        }
        for (Map.Entry<String, String> e : requested.entrySet()) {
            if (!e.getValue().equals(meterTags.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Adapts a Micrometer {@link Meter} to the immutable {@link MetricSnapshot}
     * record. The "primary value" semantics depend on the meter type — see
     * the DTO javadoc.
     */
    private MetricSnapshot toSnapshot(Meter meter, Instant timestamp) {
        Map<String, String> tags = new HashMap<>();
        for (Tag t : meter.getId().getTagsAsIterable()) {
            tags.put(t.getKey(), t.getValue());
        }
        Meter.Type type = meter.getId().getType();
        double value = primaryValue(meter);
        return new MetricSnapshot(
                meter.getId().getName(),
                Map.copyOf(tags),
                type.name().toUpperCase(Locale.ROOT),
                value,
                timestamp
        );
    }

    /**
     * Picks the most useful single number per meter type :
     * <ul>
     *   <li>Counter → {@code count()}</li>
     *   <li>Gauge / TimeGauge → {@code value()}</li>
     *   <li>Timer → {@code mean(SECONDS)}</li>
     *   <li>DistributionSummary → {@code mean()}</li>
     *   <li>LongTaskTimer → {@code activeTasks()}</li>
     *   <li>Other → first measurement value, or NaN if empty</li>
     * </ul>
     * The {@code type} field of the snapshot tells the consumer which
     * semantics apply — they shouldn't try to interpret the value
     * blindly.
     */
    private double primaryValue(Meter meter) {
        return switch (meter) {
            case Counter c -> c.count();
            case TimeGauge tg -> tg.value(TimeUnit.SECONDS);
            case Gauge g -> g.value();
            case Timer t -> t.mean(TimeUnit.SECONDS);
            case DistributionSummary ds -> ds.mean();
            case LongTaskTimer lt -> lt.activeTasks();
            default -> firstMeasurement(meter);
        };
    }

    /** Fallback for non-standard meter types. */
    private double firstMeasurement(Meter meter) {
        var iter = meter.measure().iterator();
        return iter.hasNext() ? iter.next().getValue() : Double.NaN;
    }
}
