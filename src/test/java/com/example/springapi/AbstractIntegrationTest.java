package com.example.springapi;

import com.example.springapi.config.TestAiConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Import(TestAiConfig.class)
public abstract class AbstractIntegrationTest {

    // Singleton pattern: one container for the entire test suite.
    // Avoids context cache misses when multiple test classes share the same base.
    static final KafkaContainer kafka;

    static {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:latest"));
        kafka.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
