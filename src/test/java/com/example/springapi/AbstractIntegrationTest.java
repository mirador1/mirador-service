package com.example.springapi;

import com.example.springapi.config.TestAiConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for all integration tests.
 *
 * <p>Starts three Testcontainers singletons (one per JVM) shared by the entire test suite:
 * <ul>
 *   <li><b>PostgreSQL</b> — provided via the Testcontainers JDBC URL
 *       ({@code jdbc:tc:postgresql:17:///demo}) in {@code application.properties}. No explicit
 *       container declaration is needed; the TC driver starts the container automatically.</li>
 *   <li><b>Kafka</b> — Apache Kafka in KRaft mode. The bootstrap-servers URL is wired
 *       via {@link DynamicPropertySource} so Spring resolves it before the application context
 *       starts.</li>
 *   <li><b>Redis</b> — Redis 7 for the {@code RecentCustomerBuffer}. Host and port are
 *       wired via {@link DynamicPropertySource} as {@code spring.data.redis.host/port}.</li>
 * </ul>
 *
 * <p>The singleton pattern (static + explicit {@code start()}) ensures all three containers
 * are reused across test classes in the same JVM. Starting them once amortises the Docker
 * pull + startup cost over the entire test suite, which is critical for CI build time.
 *
 * <p>Test AI configuration is imported via {@link TestAiConfig} to replace the real
 * {@link com.example.springapi.integration.BioService} with a Mockito stub (no Ollama needed).
 */
@SpringBootTest
@Import(TestAiConfig.class)
public abstract class AbstractIntegrationTest {

    // ─── Kafka container ──────────────────────────────────────────────────────
    // Singleton: one container for the entire test suite.
    // Avoids context cache misses when multiple test classes share the same base.
    static final KafkaContainer kafka;

    // ─── Redis container ──────────────────────────────────────────────────────
    // GenericContainer with the official Redis 7 image.
    // @SuppressWarnings: raw type is idiomatic for GenericContainer — no parameterisation needed.
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis;

    static {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:latest"));
        redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
                .withExposedPorts(6379);
        kafka.start();
        redis.start();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
