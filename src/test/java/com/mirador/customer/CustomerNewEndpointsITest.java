package com.mirador.customer;

import com.mirador.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for endpoints added in the latest iteration:
 * cursor pagination, search, batch import, CSV export, PUT, DELETE,
 * slow query, API key auth, page size guard, and Link headers.
 */
@AutoConfigureMockMvc
class CustomerNewEndpointsITest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired CustomerRepository customerRepository;

    @BeforeEach
    void cleanDatabase() {
        customerRepository.deleteAll();
    }

    private void createCustomer(String name, String email) throws Exception {
        mockMvc.perform(post("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"%s","email":"%s"}
                                """.formatted(name, email)))
                .andExpect(status().isOk());
    }

    // ─── Search ─────────────────────────────────────────────────────────────

    @Test
    void search_byName_returnMatchingCustomers() throws Exception {
        createCustomer("Alice Smith", "alice@example.com");
        createCustomer("Bob Jones", "bob@example.com");

        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .param("search", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("Alice Smith"));
    }

    @Test
    void search_byEmail_returnMatchingCustomers() throws Exception {
        createCustomer("Alice", "alice@demo.com");
        createCustomer("Bob", "bob@example.com");

        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .param("search", "demo.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].email").value("alice@demo.com"));
    }

    @Test
    void search_caseInsensitive() throws Exception {
        createCustomer("Alice", "alice@example.com");

        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .param("search", "ALICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void search_noMatch_returnsEmpty() throws Exception {
        createCustomer("Alice", "alice@example.com");

        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .param("search", "nonexistent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    // ─── Summary projection ─────────────────────────────────────────────────

    @Test
    void summary_returnsIdAndNameOnly() throws Exception {
        createCustomer("Alice", "alice@example.com");

        mockMvc.perform(get("/customers/summary")
                        .with(user("user").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").isNumber())
                .andExpect(jsonPath("$.content[0].name").value("Alice"));
    }

    // ─── Cursor pagination ──────────────────────────────────────────────────

    @Test
    void cursor_returnsPageWithNextCursor() throws Exception {
        for (int i = 1; i <= 5; i++) {
            createCustomer("User" + i, "user" + i + "@example.com");
        }

        mockMvc.perform(get("/customers/cursor")
                        .with(user("admin").roles("ADMIN"))
                        .param("cursor", "0")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").isNumber());
    }

    @Test
    void cursor_lastPage_hasNextIsFalse() throws Exception {
        createCustomer("Solo", "solo@example.com");

        mockMvc.perform(get("/customers/cursor")
                        .with(user("admin").roles("ADMIN"))
                        .param("cursor", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    // ─── Batch import ───────────────────────────────────────────────────────

    @Test
    void batch_createsMultipleCustomers() throws Exception {
        mockMvc.perform(post("/customers/batch")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                [
                                  {"name":"Batch1","email":"b1@example.com"},
                                  {"name":"Batch2","email":"b2@example.com"}
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.customers", hasSize(2)));
    }

    @Test
    void batch_skipsDuplicateEmails() throws Exception {
        createCustomer("Existing", "existing@example.com");

        mockMvc.perform(post("/customers/batch")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                [
                                  {"name":"New","email":"new@example.com"},
                                  {"name":"Dup","email":"existing@example.com"}
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors[0].reason").value("Email already exists"));
    }

    @Test
    void batch_allowedForUser() throws Exception {
        // ROLE_USER (read + write) can call batch — 200 with empty results
        mockMvc.perform(post("/customers/batch")
                        .with(user("user").roles("USER"))
                        .contentType(APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());
    }

    @Test
    void batch_forbiddenForReader() throws Exception {
        // ROLE_READER (read-only) is denied write operations — 403 Forbidden
        mockMvc.perform(post("/customers/batch")
                        .with(user("viewer").roles("READER"))
                        .contentType(APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isForbidden());
    }

    // ─── CSV export ─────────────────────────────────────────────────────────

    @Test
    void export_returnsCsvWithHeaders() throws Exception {
        createCustomer("Alice", "alice@example.com");

        mockMvc.perform(get("/customers/export")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=customers.csv"));
    }

    // ─── PUT /customers/{id} ────────────────────────────────────────────────

    @Test
    void update_modifiesCustomer() throws Exception {
        createCustomer("Original", "original@example.com");
        Long id = customerRepository.findAll().getFirst().getId();

        mockMvc.perform(put("/customers/{id}", id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Updated","email":"updated@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"))
                .andExpect(jsonPath("$.email").value("updated@example.com"));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        mockMvc.perform(put("/customers/99999")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"X","email":"x@example.com"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_allowedForUser() throws Exception {
        // ROLE_USER (read + write) can update customers — create first then update
        createCustomer("ToUpdate", "toupdate@example.com");
        Long id = customerRepository.findAll().getFirst().getId();

        mockMvc.perform(put("/customers/{id}", id)
                        .with(user("user").roles("USER"))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"Updated By User","email":"updated-by-user@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated By User"));
    }

    @Test
    void update_forbiddenForReader() throws Exception {
        // ROLE_READER (read-only) is denied write operations — 403 Forbidden
        mockMvc.perform(put("/customers/1")
                        .with(user("viewer").roles("READER"))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"X","email":"x@example.com"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_forbiddenForUser() throws Exception {
        // ROLE_USER (read + write) cannot delete — only ROLE_ADMIN can
        createCustomer("Protected", "protected@example.com");
        Long id = customerRepository.findAll().getFirst().getId();

        mockMvc.perform(delete("/customers/{id}", id)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    // ─── DELETE /customers/{id} ─────────────────────────────────────────────

    @Test
    void delete_removesCustomer() throws Exception {
        createCustomer("ToDelete", "delete@example.com");
        Long id = customerRepository.findAll().getFirst().getId();

        mockMvc.perform(delete("/customers/{id}", id)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/customers/99999")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_requiresAdminRole() throws Exception {
        mockMvc.perform(delete("/customers/1")
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    // ─── Slow query ─────────────────────────────────────────────────────────

    @Test
    void slowQuery_completesAndReturnsStatus() throws Exception {
        mockMvc.perform(get("/customers/slow-query")
                        .with(user("admin").roles("ADMIN"))
                        .param("seconds", "0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));
    }

    // ─── Page size guard ────────────────────────────────────────────────────

    @Test
    void pageSizeGuard_capsAt100() throws Exception {
        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    // ─── Link headers ───────────────────────────────────────────────────────

    @Test
    void linkHeaders_presentOnPaginatedResponse() throws Exception {
        for (int i = 1; i <= 5; i++) {
            createCustomer("User" + i, "u" + i + "@example.com");
        }

        mockMvc.perform(get("/customers")
                        .with(user("admin").roles("ADMIN"))
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Link"))
                .andExpect(header().string("Link", containsString("rel=\"next\"")))
                .andExpect(header().string("Link", containsString("rel=\"first\"")))
                .andExpect(header().string("Link", containsString("rel=\"last\"")));
    }

    // ─── PATCH /customers/{id} ──────────────────────────────────────────────

    @Test
    void patch_updatesOnlyProvidedFields() throws Exception {
        createCustomer("Original", "original@example.com");
        Long id = customerRepository.findAll().getFirst().getId();

        // Patch only the name — email should remain unchanged
        mockMvc.perform(patch("/customers/{id}", id)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Patched Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Patched Name"))
                .andExpect(jsonPath("$.email").value("original@example.com"));
    }

    @Test
    void patch_forbiddenForReader() throws Exception {
        mockMvc.perform(patch("/customers/1")
                        .with(user("viewer").roles("READER"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    // ─── Cache eviction ─────────────────────────────────────────────────────

    @Test
    void update_evictsCacheOnWrite() throws Exception {
        createCustomer("Cached", "cached@example.com");
        Long id = customerRepository.findAll().getFirst().getId();
        // Read to populate the Caffeine cache
        mockMvc.perform(get("/customers/{id}", id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Cached"));
        // Update — should evict the cache entry for this customer
        mockMvc.perform(put("/customers/{id}", id).with(user("admin").roles("ADMIN"))
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"Updated\",\"email\":\"updated@example.com\"}"))
                .andExpect(status().isOk());
        // Read again — must reflect the updated name (not the cached stale value)
        mockMvc.perform(get("/customers/{id}", id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Updated"));
    }

    // ─── API key auth ───────────────────────────────────────────────────────

    @Test
    void apiKey_authenticatesWithoutJwt() throws Exception {
        createCustomer("KeyTest", "keytest@example.com");

        mockMvc.perform(get("/customers")
                        .header("X-API-Key", "demo-api-key-2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void apiKey_wrongKey_returns401() throws Exception {
        mockMvc.perform(get("/customers")
                        .header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }
}
