package com.example.springapi.kafka;

import com.example.springapi.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class KafkaPatternITest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @BeforeEach
    void waitForConsumerAssignments() throws InterruptedException {
        // Wait for all @KafkaListener containers to receive their partition assignments
        // before running tests — avoids race condition on cold-start consumer groups.
        for (var container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }
    }

    @Test
    void pattern1_createPublishesEventWithoutBlocking() throws Exception {
        // POST returns immediately — event is published async to Kafka
        mockMvc.perform(post("/customers")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Alice", "email": "alice@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void pattern2_enrichReturnsDisplayNameViaSyncKafkaReply() throws Exception {
        // Create a customer first
        MvcResult result = mockMvc.perform(post("/customers")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Bob", "email": "bob@example.com"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        // Extract id from response body
        String body = result.getResponse().getContentAsString();
        Number idNumber = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        long id = idNumber.longValue();

        // GET /customers/{id}/enrich — synchronous round-trip through Kafka
        mockMvc.perform(get("/customers/{id}/enrich", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Bob"))
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.displayName").value("Bob <bob@example.com>"));
    }
}
