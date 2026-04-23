package com.mirador.resilience;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ShedLockConfig#lockProvider(DataSource)} — pin
 * the JDBC-backed lock provider wiring.
 *
 * <p>Pinned contract: the bean returns a {@link JdbcTemplateLockProvider}
 * (NOT another LockProvider implementation like RedisLockProvider). When
 * we eventually add a Redis backend (per the open horizontal-scaling
 * roadmap), it MUST be a deliberate switch, not an accidental change.
 */
class ShedLockConfigTest {

    @Test
    void lockProvider_returnsJdbcTemplateLockProviderWithGivenDataSource() {
        // Pinned: we use the JDBC backend (Postgres `shedlock` table from
        // Flyway V2). Switching to RedisLockProvider would require
        // provisioning Redis + adding HA story + ADR. This test catches
        // any silent backend swap.
        ShedLockConfig config = new ShedLockConfig();
        DataSource ds = mock(DataSource.class);

        LockProvider provider = config.lockProvider(ds);

        assertThat(provider).isInstanceOf(JdbcTemplateLockProvider.class);
    }

    @Test
    void lockProvider_returnsNonNullForAnyDataSource() {
        // Defensive: null DataSource passes through to the provider's
        // constructor — JdbcTemplate accepts null in builder phase but
        // would NPE at first lock acquisition. Not our test to enforce
        // null-handling on the upstream library; we just verify the
        // bean construction itself doesn't crash.
        ShedLockConfig config = new ShedLockConfig();

        LockProvider provider = config.lockProvider(mock(DataSource.class));

        assertThat(provider).isNotNull();
    }
}
