package com.example.springapi.customer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RecentCustomerBuffer} — no Spring context needed.
 *
 * Tests cover:
 * <ul>
 *   <li>Size boundary: buffer never exceeds 10 entries; oldest is evicted on overflow.</li>
 *   <li>Ordering: most recently added customer is always at index 0.</li>
 *   <li>Thread-safety: concurrent adds from multiple threads must not lose updates
 *       or corrupt the internal list.</li>
 * </ul>
 */
class RecentCustomerBufferTest {

    private final RecentCustomerBuffer buffer = new RecentCustomerBuffer();

    private static CustomerDto dto(long id) {
        return new CustomerDto(id, "Name" + id, "user" + id + "@example.com");
    }

    @Test
    void add_singleEntry_returnsSingletonList() {
        buffer.add(dto(1));

        assertThat(buffer.getRecent()).hasSize(1);
        assertThat(buffer.getRecent().get(0).id()).isEqualTo(1);
    }

    @Test
    void add_tenEntries_fillsBufferExactly() {
        for (int i = 1; i <= 10; i++) {
            buffer.add(dto(i));
        }

        assertThat(buffer.getRecent()).hasSize(10);
    }

    @Test
    void add_eleventhEntry_evictsOldestAndCapsAtTen() {
        for (int i = 1; i <= 11; i++) {
            buffer.add(dto(i));
        }

        List<CustomerDto> recent = buffer.getRecent();
        assertThat(recent).hasSize(10);
        // Most recent (11) is at index 0; oldest surviving (2) is at index 9
        assertThat(recent.get(0).id()).isEqualTo(11);
        assertThat(recent.get(9).id()).isEqualTo(2);
        // Customer 1 (the oldest) must have been evicted
        assertThat(recent).noneMatch(c -> c.id() == 1);
    }

    @Test
    void getRecent_returnsImmutableSnapshot() {
        buffer.add(dto(1));

        List<CustomerDto> snapshot = buffer.getRecent();
        // Mutations to the returned list must not affect internal buffer state
        assertThat(snapshot).hasSize(1);
        // List.copyOf result is unmodifiable — verify by catching UnsupportedOperationException
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(dto(99)));
    }

    @Test
    void concurrentAdds_doNotCorruptBuffer() throws InterruptedException {
        int threads = 20;
        int addsPerThread = 5;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                final int base = t * addsPerThread;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (int i = 0; i < addsPerThread; i++) {
                            buffer.add(dto(base + i + 1));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                    return null;
                });
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown(); // release all threads simultaneously
            done.await(10, TimeUnit.SECONDS);
        }

        List<CustomerDto> result = buffer.getRecent();
        // After 100 concurrent adds, size must be exactly 10 (capped)
        assertThat(result).hasSize(10);
        // All entries must have positive IDs (no nulls or zero sentinel values)
        assertThat(result).allMatch(c -> c.id() > 0);
    }
}
