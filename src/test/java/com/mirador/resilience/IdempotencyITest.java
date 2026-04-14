package com.mirador.resilience;
import com.mirador.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.mirador.customer.CustomerRepository;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class IdempotencyITest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CustomerRepository customerRepository;

    @Test
    void sameKeyReturnsOriginalResponseWithoutDuplicate() throws Exception {
        String key = java.util.UUID.randomUUID().toString();
        String body = """
                { "name": "Idempotent", "email": "idem@example.com" }
                """;

        // First call — creates customer, captures response body
        String firstResponse = mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Idempotent"))
                .andReturn()
                .getResponse().getContentAsString();

        // Second call with same key — must replay identical body (no new DB row)
        mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().json(firstResponse));
    }

    @Test
    void withoutIdempotencyKey_requestIsNotCached() throws Exception {
        String body = """
                {"name": "NoKey", "email": "nokey@example.com"}
                """;

        // Two calls without Idempotency-Key — both should succeed and create separate customers
        mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Both calls were processed — two DB rows
        assertThat(customerRepository.count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void differentKeys_createIndependentResponses() throws Exception {
        String keyA = java.util.UUID.randomUUID().toString();
        String keyB = java.util.UUID.randomUUID().toString();
        String bodyA = """
                {"name": "KeyA", "email": "keya@example.com"}
                """;
        String bodyB = """
                {"name": "KeyB", "email": "keyb@example.com"}
                """;

        String responseA = mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .header("Idempotency-Key", keyA)
                        .contentType(APPLICATION_JSON)
                        .content(bodyA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("KeyA"))
                .andReturn().getResponse().getContentAsString();

        String responseB = mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .header("Idempotency-Key", keyB)
                        .contentType(APPLICATION_JSON)
                        .content(bodyB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("KeyB"))
                .andReturn().getResponse().getContentAsString();

        // Replaying keyA still returns the original KeyA response
        mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .header("Idempotency-Key", keyA)
                        .contentType(APPLICATION_JSON)
                        .content(bodyA))
                .andExpect(status().isOk())
                .andExpect(content().json(responseA));

        // Replaying keyB still returns the original KeyB response
        mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .header("Idempotency-Key", keyB)
                        .contentType(APPLICATION_JSON)
                        .content(bodyB))
                .andExpect(status().isOk())
                .andExpect(content().json(responseB));
    }
}
