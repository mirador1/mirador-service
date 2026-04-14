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
 *   • process_cpu       — CPU samples via ITIMER (on-CPU time)
 *   • wall              — wall-clock samples (on-CPU + blocked on I/O, locks, sleep)
 *   • memory alloc      — allocation bytes/objects in TLAB and outside TLAB
 *   • lock              — lock contention (monitors held > 10ms)
 *
 * ITIMER shows what the CPU is actively doing.
 * WALL reveals threads blocked waiting for Kafka, DB, Ollama, or HTTP responses.
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

        // Agent 1 — CPU (ITIMER): shows which methods consume CPU time
        PyroscopeAgent.start(
                new Config.Builder()
                        .setApplicationName("customer-service")
                        .setProfilingEvent(EventType.ITIMER)
                        .setProfilingAlloc("512k")   // 2 extra types: alloc_in_new_tlab + alloc_outside_tlab
                        .setProfilingLock("10ms")    // 1 extra type: lock contention > 10ms
                        .setFormat(Format.JFR)
                        .setServerAddress(serverAddress)
                        .setLabels(labels)
                        .build()
        );

        // Agent 2 — Wall clock: shows threads blocked on I/O, Kafka, DB, Ollama, sleep
        // Runs alongside the CPU agent, pushes under a separate profile type "wall"
        PyroscopeAgent.start(
                new Config.Builder()
                        .setApplicationName("customer-service")
                        .setProfilingEvent(EventType.WALL)
                        .setFormat(Format.JFR)
                        .setServerAddress(serverAddress)
                        .setLabels(labels)
                        .build()
        );

        log.info("Pyroscope agent started (CPU+alloc+lock+wall) — profiles at {}", serverAddress);
    }
}
