package com.example.springapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest
@AutoConfigureMockMvc
class CustomerApiTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void shouldCreateAndListCustomers() throws Exception {
        mockMvc.perform(post("/customers")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Benoit",
                                  "email": "benoit@example.com"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Benoit"))
                .andExpect(jsonPath("$.email").value("benoit@example.com"));

        mockMvc.perform(get("/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Benoit"))
                .andExpect(jsonPath("$[0].email").value("benoit@example.com"));
    }

    @Test
    void shouldReturnRequestIdHeader() throws Exception {
        mockMvc.perform(get("/customers"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void shouldPropagateIncomingRequestId() throws Exception {
        mockMvc.perform(get("/customers")
                        .header("X-Request-Id", "req-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-123"));
    }

}