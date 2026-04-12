package com.example.springapi.auth;
import com.example.springapi.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthITest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void login_withValidCredentials_returnsToken() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void login_withInvalidPassword_returns401() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/customers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withValidToken_returns200() throws Exception {
        // obtain token
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String token = com.jayway.jsonpath.JsonPath.read(
                loginResult.getResponse().getContentAsString(), "$.token");

        mockMvc.perform(get("/customers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_withTamperedToken_returns401() throws Exception {
        mockMvc.perform(get("/customers")
                        .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.tampered.sig"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_withMissingPassword_returns4xx() throws Exception {
        // Missing required field — Bean Validation should reject or AuthController should return 401
        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"admin"}
                                """))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void login_withEmptyBody_returns4xx() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
