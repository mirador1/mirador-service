package com.mirador.customer;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerStatsSchedulerTest {

    @Mock
    private CustomerRepository repository;

    @InjectMocks
    private CustomerStatsScheduler scheduler;

    @Test
    void logCustomerStats_delegatesToRepository() {
        // When the scheduler fires, it must call repository.count() exactly once.
        // The result is only logged — no return value to assert — so verifying the
        // interaction is the correct contract to test here.
        when(repository.count()).thenReturn(42L);

        scheduler.logCustomerStats();

        verify(repository).count();
    }

    @Test
    void logCustomerStats_doesNotThrowWhenCountIsZero() {
        // Edge case: empty database — count() returns 0, no exception expected.
        when(repository.count()).thenReturn(0L);

        scheduler.logCustomerStats();

        verify(repository).count();
    }
}
