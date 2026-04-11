package com.example.springapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class IdempotencyITest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void sameKeyReturnsOriginalResponseWithoutDuplicate() throws Exception {
        String key = java.util.UUID.randomUUID().toString();
        String body = """
                { "name": "Idempotent", "email": "idem@example.com" }
                """;

        // First call — creates customer, captures response body
        String firstResponse = mockMvc.perform(post("/customers")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Idempotent"))
                .andReturn()
                .getResponse().getContentAsString();

        // Second call with same key — must replay identical body (no new DB row)
        mockMvc.perform(post("/customers")
                        .header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().json(firstResponse));
    }
}
