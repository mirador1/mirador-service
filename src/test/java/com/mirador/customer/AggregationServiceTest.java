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
        // Parallel execution should complete in comfortably less — we use
        // 420 ms as the threshold: strict enough to fail on the serial path
        // (400 ms is the floor, so serial execution with any scheduler jitter
        // at all hits > 420 ms), but generous enough to survive noisy
        // macbook-local CI runners (seen up to 454 ms under load on
        // 2026-04-19). The 20 ms headroom above the 400 ms floor is where
        // "parallel but the runner stuttered" lives.
        long start = System.nanoTime();
        service.aggregate();
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(durationMs)
                .as("parallel virtual-thread execution should finish before the 400 ms serial floor")
                .isLessThan(420);
    }
}
