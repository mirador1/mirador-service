package com.mirador.customer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AggregationService} — no Spring context needed.
 *
 * The key behaviour to verify:
 * <ul>
 *   <li>Parallel execution: both sub-tasks take ~200 ms each; serial execution would take ~400 ms.
 *       With virtual-thread parallelism the total should be well below 400 ms.</li>
 *   <li>Result correctness: the returned DTO contains the expected field values.</li>
 * </ul>
 */
class AggregationServiceTest {

    private final AggregationService service = new AggregationService();

    @Test
    void aggregate_returnsExpectedData() {
        AggregationService.AggregatedResponse response = service.aggregate();

        assertThat(response.customerData()).isEqualTo("customer-data");
        assertThat(response.stats()).isEqualTo("stats");
    }

    @Test
    void aggregate_runsBothTasksInParallel() {
        // Each sub-task sleeps 200 ms. Serial execution would take ≥ 400 ms.
        // Parallel execution should complete in < 360 ms (200 ms + some overhead).
        long start = System.nanoTime();
        service.aggregate();
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        // Use 360 ms as the threshold: generous enough to avoid flakiness on slow CI,
        // strict enough to confirm parallelism (not 400+ ms serial path).
        assertThat(durationMs)
                .as("parallel virtual-thread execution should finish well under 400 ms")
                .isLessThan(360);
    }
}
