package org.iris.ml;

import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link MlConfig}'s single bean factory method.
 *
 * <p>Pinned : the bean returns a UTC-based clock so feature extraction
 * computes "days since X" using a stable timezone, regardless of the
 * server's local zone.
 */
class MlConfigTest {

    @Test
    void systemClock_returnsUtcClock() {
        MlConfig config = new MlConfig();
        Clock clock = config.systemClock();
        assertThat(clock).isNotNull();
        assertThat(clock.getZone()).isEqualTo(java.time.ZoneOffset.UTC);
    }
}
