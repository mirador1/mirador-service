package com.example.springapi.scheduler;

import com.example.springapi.repository.CustomerRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CustomerStatsScheduler {

    private static final Logger log = LoggerFactory.getLogger(CustomerStatsScheduler.class);

    private final CustomerRepository repository;

    public CustomerStatsScheduler(CustomerRepository repository) {
        this.repository = repository;
    }

    // Toutes les 30s, log le nombre total de customers (démo simple)
    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "customerStats", lockAtMostFor = "PT25S", lockAtLeastFor = "PT10S")
    public void logCustomerStats() {
        long count = repository.count();
        log.info("customer_stats total={}", count);
    }
}
