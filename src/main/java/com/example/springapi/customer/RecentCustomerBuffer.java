package com.example.springapi.service;

import com.example.springapi.dto.CustomerDto;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;
import java.util.SequencedCollection;

/**
 * Thread-safe in-memory ring buffer holding the 10 most recently created customers.
 *
 * <p>This is an intentionally simple bounded cache:
 * <ul>
 *   <li>New customers are prepended ({@code addFirst}) so the most recent is always at index 0.</li>
 *   <li>The list is capped at 10 entries by removing the tail when it overflows.</li>
 *   <li>{@code synchronized} on both mutating and reading methods provides a simple but coarse
 *       mutual exclusion. For high-throughput scenarios, a {@code ReentrantReadWriteLock} or
 *       a lock-free structure (e.g., Disruptor) would be preferable.</li>
 * </ul>
 *
 * <p>The buffer is populated in {@link CustomerService#create} and read by
 * {@code GET /customers/recent} without a database query, demonstrating a useful pattern
 * for "hot" or frequently-read small data sets that do not require persistence.
 *
 * <p>A Micrometer {@code Gauge} registered in {@link com.example.springapi.config.ObservabilityConfig}
 * tracks the current size of this buffer and publishes it to Prometheus as
 * {@code customer.recent.buffer.size}.
 */
@Service
public class RecentCustomerBuffer {

    private final LinkedList<CustomerDto> recent = new LinkedList<>();

    /**
     * Adds a customer to the front of the list (most recent first).
     * If the list exceeds 10 entries, the oldest entry is dropped from the tail.
     */
    public synchronized void add(CustomerDto dto) {
        recent.addFirst(dto);
        // Bounded to 10 entries — the oldest is evicted when the cap is exceeded
        if (recent.size() > 10) {
            recent.removeLast();
        }
    }

    /**
     * Returns a snapshot of the recent-customers list.
     * {@code List.copyOf()} creates an immutable copy so the caller cannot modify the internal state.
     * The {@link SequencedCollection} type (Java 21+) is used here explicitly to document
     * that insertion order is guaranteed by the {@link LinkedList}.
     */
    public synchronized List<CustomerDto> getRecent() {
        SequencedCollection<CustomerDto> view = recent;
        return List.copyOf(view);
    }
}
