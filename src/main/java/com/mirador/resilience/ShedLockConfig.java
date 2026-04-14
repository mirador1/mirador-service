package com.mirador.resilience;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock distributed lock provider backed by the PostgreSQL {@code shedlock} table.
 *
 * <h3>What is ShedLock?</h3>
 * <p>When multiple instances of the application run simultaneously (e.g., horizontal scaling
 * in Kubernetes), {@code @Scheduled} methods execute on every instance at the same time.
 * ShedLock prevents this by acquiring a database-level advisory lock before each execution:
 * only the instance that wins the lock runs the scheduled task; all others skip it.
 *
 * <h3>Lock table</h3>
 * <p>The {@code shedlock} table is created by Flyway migration V2 and has three columns:
 * <ul>
 *   <li>{@code name} — unique task name (matches {@code @SchedulerLock(name = "...")})</li>
 *   <li>{@code lock_until} — timestamp until which the lock is held</li>
 *   <li>{@code locked_at} — when the lock was acquired</li>
 * </ul>
 *
 * <h3>usingDbTime()</h3>
 * <p>{@code usingDbTime()} tells ShedLock to use the database clock (PostgreSQL {@code NOW()})
 * instead of the application server clock for lock timestamps. This avoids issues when
 * application instances have slightly different system clocks (clock skew), which could
 * cause a lock to appear expired prematurely on one node.
 *
 * <h3>Lock parameters (configured on {@link com.mirador.scheduler.CustomerStatsScheduler})</h3>
 * <ul>
 *   <li>{@code lockAtMostFor = "PT25S"} — release the lock after 25 s even if the task hasn't
 *       finished (safety valve against hangs)</li>
 *   <li>{@code lockAtLeastFor = "PT10S"} — hold the lock for at least 10 s even if the task
 *       finishes quickly (prevents rapid re-execution when multiple instances race)</li>
 * </ul>
 *
 * <p>ShedLock is activated at the application level by {@code @EnableSchedulerLock} on
 * {@link com.mirador.CustomerServiceApplication}.
 */
@Configuration
public class ShedLockConfig {

    /**
     * Registers the JDBC-backed lock provider.
     * Uses the same {@link DataSource} as JPA — no additional database connection needed.
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        // Use database clock for timestamps to avoid clock skew between instances
                        .usingDbTime()
                        .build()
        );
    }
}
