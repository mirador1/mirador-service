package com.mirador.customer;

import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

/**
 * Demonstrates parallel data loading using Java 21+ virtual threads (Project Loom).
 *
 * <p>The {@code aggregate()} method runs two independent tasks concurrently on a
 * virtual-thread-per-task executor:
 * <ul>
 *   <li>{@code loadCustomerData()} — simulates a slow customer data fetch (200 ms).</li>
 *   <li>{@code loadStats()} — simulates a slow statistics query (200 ms).</li>
 * </ul>
 *
 * <p>Because both tasks run in parallel, the total latency is ~200 ms instead of ~400 ms.
 * Virtual threads are cheap to create (backed by OS carrier threads, not one-to-one with OS threads),
 * so spawning one per sub-task is idiomatic in Java 21+ and replaces the older
 * {@code ForkJoinPool} / CompletableFuture patterns for I/O-bound work.
 *
 * <p>The try-with-resources on the executor ensures it is shut down (joining all tasks)
 * when the block exits, implementing the structured-concurrency contract: no task outlives
 * the scope that launched it.
 *
 * <p>The endpoint {@code GET /customers/aggregate} intentionally has a fixed 200 ms latency
 * so that the Grafana dashboard can visually demonstrate the impact of aggregation endpoints
 * on tail latency (p95/p99).
 */
@Service
public class AggregationService {

    /**
     * Runs both sub-tasks in parallel and returns their combined result.
     *
     * <p>{@code newVirtualThreadPerTaskExecutor()} creates a new virtual thread for each
     * submitted task. The {@code Future.get()} calls are sequential here, but since both
     * futures were submitted before either {@code get()} is called, they execute in parallel.
     */
    public AggregatedResponse aggregate() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) { // [Java 21+] virtual threads
            var customerFuture = executor.submit(this::loadCustomerData);
            var statsFuture = executor.submit(this::loadStats);

            return new AggregatedResponse(
                    customerFuture.get(),
                    statsFuture.get()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interruption", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Aggregation failed", e);
        }
    }

    /** Simulates a slow customer data source — 200 ms latency to make parallelism visible in traces. */
    private String loadCustomerData() throws InterruptedException {
        Thread.sleep(200);
        return "customer-data";
    }

    /** Simulates a slow statistics query — 200 ms latency to make parallelism visible in traces. */
    private String loadStats() throws InterruptedException {
        Thread.sleep(200);
        return "stats";
    }

    /** Aggregated response DTO — a Java record (immutable, compact, no boilerplate). */
    public record AggregatedResponse(String customerData, String stats) {
    }
}
