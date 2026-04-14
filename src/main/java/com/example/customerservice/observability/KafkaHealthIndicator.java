package com.example.customerservice.observability;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Custom health indicator that verifies Kafka broker reachability.
 *
 * <p>Opens a short-lived {@link AdminClient} connection and calls
 * {@code describeCluster()} with a 3-second timeout. This verifies:
 * <ul>
 *   <li>At least one broker is reachable at the configured bootstrap servers.</li>
 *   <li>The cluster ID is returned (proves the broker is fully operational).</li>
 * </ul>
 *
 * <p>Appears at {@code /actuator/health} as the {@code kafka} component.
 *
 * <h3>Diagnostic scenario</h3>
 * <p>{@code docker compose stop kafka} → poll {@code GET /actuator/health/readiness}
 * to see the Kafka component transition from UP to DOWN.
 */
@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

    private final String bootstrapServers;

    public KafkaHealthIndicator(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    @Override
    public Health health() {
        try (AdminClient client = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                       AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000,
                       AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3_000))) {
            var options = new DescribeClusterOptions().timeoutMs(3_000);
            String clusterId = client.describeCluster(options)
                    .clusterId()
                    .get(5, TimeUnit.SECONDS);
            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();
        }
    }
}
