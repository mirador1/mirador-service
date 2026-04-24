package com.mirador.customer;

import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Demonstrates parallel data loading.
 *
 * <p><b>Java 17 variant</b> — uses {@code Executors.newCachedThreadPool()} (platform threads)
 * instead of {@code Executors.newVirtualThreadPerTaskExecutor()} which is a Java 21+ API.
 * Functionally equivalent for this 2-task aggregation : both run the sub-tasks in parallel
 * via the executor, and total latency stays ~200 ms.
 *
 * <p>Trade-off vs the Java 21+ variant : platform threads are heavier (1:1 OS thread
 * mapping, ~1 MB stack each) ; for 2 tasks it doesn't matter, but the J21+ version
 * scales to thousands of concurrent submits without issue. Compat-mode here only
 * needs the demo to compile + behave correctly, not match the same scaling profile.
 *
 * <p>The cached thread pool is bounded only by JVM thread limits, NOT the
 * `app.aggregation.parallelism` config — for 2 fixed sub-tasks we don't need a
 * limit. The `try-with-resources` + ExecutorService.close() (Java 19+, available
 * in J17 via the Closeable interface that ExecutorService extends) ensures the
 * pool is shut down at scope exit, matching the structured-concurrency behaviour
 * of the J21+ variant. (J17 doesn't have ExecutorService.close() — wrapped via
 * try/finally with shutdown() instead.)
 */
@Service
public class AggregationService {

    /**
     * Runs both sub-tasks in parallel and returns their combined result.
     *
     * <p>J17 uses platform threads via {@code Executors.newCachedThreadPool()}.
     * Both Future submits happen before either {@code get()} call, so they
     * execute in parallel.
     */
    public AggregatedResponse aggregate() {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
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
        } finally {
            executor.shutdown();
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
