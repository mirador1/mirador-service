package com.mirador.observability.quality.providers;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Reads {@code META-INF/build-info.properties} (produced by Spring Boot's
 * {@code build-info} Maven goal) and assembles the {@code build} section.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildBuildSection}
 * under Phase B-1b (non-parser section providers). First provider class
 * under {@code quality.providers} — followed by Api, Git, Deps, Metrics,
 * Sonar, Pipeline, Branches, Runtime when those follow.
 *
 * <h3>Output</h3>
 * <pre>
 * { available: true,
 *   artifact,           // e.g. "mirador"
 *   version,            // e.g. "1.0.12-SNAPSHOT"
 *   time,               // ISO-8601 build timestamp
 *   javaVersion,        // JVM system property
 *   springBootVersion } // read from build.version (kept for dashboard compat)
 * </pre>
 * {@code {available: false}} when the properties file isn't on the
 * classpath (e.g. running from IDE without the build-info goal).
 */
@Component
public class BuildInfoSectionProvider {

    private static final String CP_BUILD_INFO = "META-INF/build-info.properties";

    private static final String K_AVAILABLE = "available";
    private static final String K_ERROR     = "error";
    private static final String K_VERSION   = "version";
    private static final String K_UNKNOWN   = "unknown";

    public Map<String, Object> parse() {
        ClassPathResource res = new ClassPathResource(CP_BUILD_INFO);
        if (!res.exists()) {
            return Map.of(K_AVAILABLE, false);
        }

        Properties props = new Properties();
        try (InputStream is = res.getInputStream()) {
            props.load(is);
        } catch (IOException e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(K_AVAILABLE, true);
        result.put("artifact",          props.getProperty("build.artifact", K_UNKNOWN));
        result.put(K_VERSION,           props.getProperty("build.version",  K_UNKNOWN));
        result.put("time",              props.getProperty("build.time",     K_UNKNOWN));
        result.put("javaVersion",       System.getProperty("java.version",  K_UNKNOWN));
        // Historical: spring-boot version is embedded in build.version; the
        // dashboard shows this field separately. Kept for UI compatibility.
        result.put("springBootVersion", props.getProperty("build.version",  K_UNKNOWN));
        return result;
    }
}
