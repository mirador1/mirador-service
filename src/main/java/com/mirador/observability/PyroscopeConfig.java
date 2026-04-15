package com.mirador.observability;

import io.pyroscope.http.Format;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Starts the Pyroscope continuous profiling agent at application startup.
 *
 * Profile types pushed to Pyroscope every 10s:
 *   • wall              — wall-clock samples (on-CPU + blocked on I/O, locks, sleep)
 *   • alloc_in_new_tlab — allocation bytes inside TLAB (fast path)
 *   • alloc_outside_tlab — allocation bytes outside TLAB (large objects)
 *   • lock              — lock contention (monitors held > 10ms)
 *
 * WALL is preferred over ITIMER for a service that does Kafka, DB, and Ollama I/O:
 * ITIMER only samples CPU-hot threads; WALL also captures threads blocked waiting
 * for network responses, which is where latency hides in this application.
 *
 * NOTE: Pyroscope Java SDK only supports ONE PyroscopeAgent.start() per JVM.
 * A second start() call is silently ignored. All desired profile types must be
 * configured in the single Config.Builder (EventType for the main type, plus
 * setProfilingAlloc and setProfilingLock for the secondary types).
 *
 * Profiles are visible at http://localhost:4040 under "customer-service".
 */
@Configuration
public class PyroscopeConfig {

    private static final Logger log = LoggerFactory.getLogger(PyroscopeConfig.class);

    @Value("${pyroscope.server-address:http://localhost:4040}")
    private String serverAddress;

    @Value("${pyroscope.enabled:true}")
    private boolean enabled;

    @PostConstruct
    public void start() {
        if (!enabled || serverAddress.isBlank()) {
            log.info("Pyroscope profiling disabled (pyroscope.enabled={}, address='{}')", enabled, serverAddress);
            return;
        }
        log.info("Starting Pyroscope profiling agent → {}", serverAddress);

        Map<String, String> labels = Map.of("region", "local", "env", "dev");

        // Single agent: WALL event type captures all threads (on-CPU + blocked on I/O).
        // setProfilingAlloc and setProfilingLock add two extra profile types automatically.
        // Result: 4 profile types — wall, alloc_in_new_tlab, alloc_outside_tlab, lock.
        PyroscopeAgent.start(
                new Config.Builder()
                        .setApplicationName("customer-service")
                        .setProfilingEvent(EventType.WALL)
                        .setProfilingAlloc("512k")   // alloc_in_new_tlab + alloc_outside_tlab
                        .setProfilingLock("10ms")    // lock contention > 10ms
                        .setFormat(Format.JFR)
                        .setServerAddress(serverAddress)
                        .setLabels(labels)
                        .build()
        );

        log.info("Pyroscope agent started (wall+alloc+lock) — 4 profile types at {}", serverAddress);
    }
}
