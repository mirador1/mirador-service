package com.example.springapi;

import com.example.springapi.customer.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@AutoConfigureMockMvc
class CustomerApiITest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CustomerRepository customerRepository;

    @BeforeEach
    void cleanDatabase() {
        customerRepository.deleteAll();
    }

    @Test
    void shouldCreateAndListCustomers() throws Exception {
        mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
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

        mockMvc.perform(get("/customers").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("Benoit"))
                .andExpect(jsonPath("$.content[0].email").value("benoit@example.com"));
    }

    @Test
    void shouldReturnRequestIdHeader() throws Exception {
        mockMvc.perform(get("/customers").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void shouldPropagateIncomingRequestId() throws Exception {
        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Request-Id", "req-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-123"));
    }

    @Test
    void shouldReturn400ForBlankName() throws Exception {
        mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "   ", "email": "valid@example.com"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400ForInvalidEmail() throws Exception {
        mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Alice", "email": "not-an-email"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400ForMissingFields() throws Exception {
        mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRespectPageSizeParameter() throws Exception {
        // Insert 3 customers
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/customers")
                            .with(user("admin").roles("ADMIN"))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"name": "User%d", "email": "user%d@example.com"}
                                    """.formatted(i, i)))
                    .andExpect(status().isOk());
        }

        // Request page 0 with size 2 — should return exactly 2 items
        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }
}
