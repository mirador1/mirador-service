package com.example.springapi.resilience;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the rate-limiting filter with a very small bucket (capacity=2)
 * so the test does not need to make hundreds of requests.
 *
 * Uses a dedicated X-Forwarded-For address to avoid polluting the shared bucket
 * used by other integration tests running in the same JVM.
 */
@SpringBootTest
@Import(com.example.springapi.config.TestAiConfig.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.rate-limit.capacity=2",
        "app.rate-limit.refill-tokens=2",
        "app.rate-limit.refill-seconds=60"
})
class RateLimitingITest {

    // Isolated Kafka container — property override forces a separate Spring context.
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("apache/kafka:latest"));

    static {
        kafka.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired MockMvc mockMvc;

    // Unique IP so the bucket is not shared with other test classes.
    private static final String TEST_IP = "10.99.88.77";

    @Test
    void afterExceedingCapacity_returns429WithRetryAfterHeader() throws Exception {
        // consume all 2 tokens
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/customers")
                            .with(user("admin").roles("USER"))
                            .header("X-Forwarded-For", TEST_IP))
                    .andExpect(status().isOk());
        }

        // 3rd request must be rejected
        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("USER"))
                        .header("X-Forwarded-For", TEST_IP))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void withinCapacity_remainingTokenHeaderIsDecremented() throws Exception {
        String ip = "10.99.88.78"; // different IP → fresh bucket

        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("USER"))
                        .header("X-Forwarded-For", ip))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Rate-Limit-Remaining", "1"));

        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("USER"))
                        .header("X-Forwarded-For", ip))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Rate-Limit-Remaining", "0"));
    }
}
