package com.mirador.mcp.actuator;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin abstraction over the Spring {@link ConfigurableEnvironment} that
 * returns a flattened map of property name → value, optionally filtered
 * by prefix.
 *
 * <h3>Why a dedicated provider, not Actuator's EnvironmentEndpoint ?</h3>
 * <p>{@code EnvironmentEndpoint.environment(String)} returns a deeply
 * nested {@code EnvironmentDescriptor} mirroring the Spring property-source
 * hierarchy — useful in the Actuator UI but noisy for an LLM that just
 * wants {@code key = value}. This provider walks the same property sources
 * directly and produces a flat map. Side benefit : we can stub it in unit
 * tests without booting a Spring context.
 *
 * <p>Iteration order follows {@link MutablePropertySources}'s property
 * source order — early sources (system properties, process environment)
 * win on duplicate keys, matching Spring's resolution order.
 */
@Component
public class EnvironmentSnapshotProvider {

    private final ConfigurableEnvironment environment;

    public EnvironmentSnapshotProvider(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    /**
     * Builds a flat property map, retaining only keys starting with the
     * supplied {@code prefix}. {@code null} or blank prefix returns all
     * keys.
     *
     * @param prefix filter prefix ({@code "spring."}, {@code "mirador."}…) ;
     *               null or blank disables the filter.
     * @return ordered map (insertion order = first occurrence wins).
     */
    public Map<String, Object> snapshot(String prefix) {
        boolean noFilter = prefix == null || prefix.isBlank();
        Map<String, Object> result = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!noFilter && !name.startsWith(prefix)) {
                    continue;
                }
                result.putIfAbsent(name, environment.getProperty(name));
            }
        }
        return result;
    }
}
