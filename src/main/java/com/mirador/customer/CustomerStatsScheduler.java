package com.mirador.customer;

import com.mirador.customer.CustomerRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Distributed scheduled task that logs the current total number of customers every 30 seconds.
 *
 * <h3>Purpose</h3>
 * <p>This scheduler demonstrates two complementary Spring mechanisms:
 * <ul>
 *   <li><b>Spring Scheduling</b> ({@code @Scheduled}) — triggers the method on a fixed delay.</li>
 *   <li><b>ShedLock</b> ({@code @SchedulerLock}) — ensures only one instance executes the
 *       task when multiple application replicas are running (e.g., in Kubernetes).</li>
 * </ul>
 *
 * <h3>fixedDelay vs fixedRate</h3>
 * <p>{@code fixedDelay = 30_000} means "wait 30 s after the previous execution completes"
 * (not "run every 30 s on the clock"). This is safer for tasks that may take variable time —
 * it prevents overlap. Use {@code fixedRate} only when strict periodicity matters and
 * execution time is negligible.
 *
 * <h3>ShedLock parameters</h3>
 * <ul>
 *   <li>{@code lockAtMostFor = "PT25S"} — if the instance that holds the lock crashes before
 *       releasing it, the lock is forcibly released after 25 s so another instance can proceed.
 *       Should be slightly less than {@code fixedDelay} to avoid skipping cycles entirely.</li>
 *   <li>{@code lockAtLeastFor = "PT10S"} — the lock is held for at least 10 s even if the
 *       task finishes instantly. Prevents other instances from running the task within the
 *       same 30 s window due to minor clock differences.</li>
 * </ul>
 *
 * <p>The scheduler is enabled at the application level by {@code @EnableScheduling} +
 * {@code @EnableSchedulerLock(defaultLockAtMostFor = "PT10M")} on
 * {@link com.mirador.CustomerServiceApplication}.
 * The lock provider is configured in {@link com.mirador.config.ShedLockConfig}.
 */
@Component
public class CustomerStatsScheduler {

    private static final Logger log = LoggerFactory.getLogger(CustomerStatsScheduler.class);

    private final CustomerRepository repository;

    public CustomerStatsScheduler(CustomerRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs every 30 s (after previous execution completes) and logs the total customer count.
     * Protected by ShedLock to prevent duplicate execution in a multi-instance deployment.
     */
    // Every 30s, log the total number of customers (simple observability demo)
    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "customerStats", lockAtMostFor = "PT25S", lockAtLeastFor = "PT10S")
    public void logCustomerStats() {
        long count = repository.count();
        log.info("customer_stats total={}", count);
    }
}
