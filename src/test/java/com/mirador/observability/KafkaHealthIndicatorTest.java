package com.mirador.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the Kafka health indicator.
 * Uses an unreachable broker to exercise the DOWN path without a live Kafka cluster.
 */
class KafkaHealthIndicatorTest {

    @Test
    void unreachableBroker_returnsDown() {
        // Port 1 is always refused; the 3-second timeout fires quickly
        var indicator = new KafkaHealthIndicator("127.0.0.1:1");
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsKey("bootstrapServers");
    }
}
