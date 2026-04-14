package com.mirador;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests that verify the Keycloak OAuth2 resource server configuration.
 *
 * <h3>What is tested</h3>
 * <ul>
 *   <li>A token issued to {@code monitoring-service} (ROLE_READER) grants read access (GET /customers)</li>
 *   <li>A token issued to {@code monitoring-service} (ROLE_READER) is denied write access (POST /customers)</li>
 *   <li>A token issued to {@code api-gateway} (ROLE_ADMIN) grants write access (POST /customers)</li>
 *   <li>A built-in JWT (from POST /auth/login) still works alongside Keycloak tokens</li>
 * </ul>
 *
 * <h3>Token acquisition</h3>
 * <p>Tokens are obtained via the Client Credentials grant ({@code grant_type=client_credentials}).
 * Each client ({@code api-gateway}, {@code monitoring-service}) authenticates with its
 * {@code client_id} and {@code client_secret}. Roles are assigned to the service account
 * associated with each client — no human users are involved.
 *
 * <h3>Container setup</h3>
 * <p>A real Keycloak 26 instance is started via {@link KeycloakContainer} (Testcontainers).
 * The realm definition at {@code src/test/resources/realm-dev.json} is imported at startup.
 * The container's issuer URI is injected into {@code keycloak.issuer-uri} so that
 * {@link com.mirador.auth.SecurityConfig} activates the OAuth2 resource server path.
 *
 * [Spring Security / Spring Boot 4] — oauth2ResourceServer().jwt() dual-mode with custom JWT.
 * [Testcontainers] — KeycloakContainer from com.github.dasniko:testcontainers-keycloak.
 */
@AutoConfigureMockMvc
@Testcontainers
class KeycloakAuthITest extends AbstractIntegrationTest {

    /**
     * Keycloak container shared across all tests in this class.
     * The realm-dev.json is imported via {@code withRealmImportFile()}.
     *
     * <p>Using {@code @Container} at the static field level ensures the container is
     * started once per test class (rather than once per test method), which avoids
     * repeated 30-second container startup times.
     */
    @Container
    static final KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.2.5")
            .withRealmImportFile("realm-dev.json")
            // Disable HTTPS for local testing — Keycloak 26 requires HTTPS by default.
            // The env vars mirror the docker-compose.yml settings.
            .withEnv("KC_HTTP_ENABLED", "true")
            .withEnv("KC_HOSTNAME_STRICT", "false")
            .withEnv("KC_HOSTNAME_STRICT_HTTPS", "false");

    /**
     * Override {@code keycloak.issuer-uri} with the Testcontainers-assigned URL.
     * This activates the OAuth2 resource server path in
     * {@link com.mirador.auth.SecurityConfig}.
     */
    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("keycloak.issuer-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/customer-service");
    }

    @Autowired
    MockMvc mockMvc;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void obtainTokens() {
        userToken  = obtainClientToken("monitoring-service", "dev-secret-readonly");
        adminToken = obtainClientToken("api-gateway",        "dev-secret");
    }

    // -------------------------------------------------------------------------
    // ROLE_READER (monitoring-service) — read-only access
    // -------------------------------------------------------------------------

    @Test
    void keycloakUser_canListCustomers() throws Exception {
        mockMvc.perform(get("/customers")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void keycloakUser_cannotCreateCustomer() throws Exception {
        mockMvc.perform(post("/customers")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Unauthorized","email":"unauth@example.com"}
                                """))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // ROLE_ADMIN — write access
    // -------------------------------------------------------------------------

    @Test
    void keycloakAdmin_canCreateCustomer() throws Exception {
        mockMvc.perform(post("/customers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Keycloak Admin","email":"kc-admin@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keycloak Admin"));
    }

    @Test
    void keycloakAdmin_canListCustomers() throws Exception {
        mockMvc.perform(get("/customers")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // No token → 401
    // -------------------------------------------------------------------------

    @Test
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/customers"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Built-in JWT (/auth/login) still works alongside Keycloak
    // -------------------------------------------------------------------------

    @Test
    void builtinJwt_stillWorksAlongsideKeycloak() throws Exception {
        var loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()))
                .andReturn();

        String builtinToken = com.jayway.jsonpath.JsonPath.read(
                loginResult.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/customers")
                        .header("Authorization", "Bearer " + builtinToken))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Obtains a Keycloak access token for a confidential client via the Client Credentials grant.
     *
     * <p>Roles are carried in the JWT via the {@code realm_access.roles} claim, populated
     * by the {@code roles} scope. The service account associated with each client has
     * its realm roles assigned in {@code realm-dev.json}.
     */
    private String obtainClientToken(String clientId, String clientSecret) {
        String tokenUrl = keycloak.getAuthServerUrl()
                + "/realms/customer-service/protocol/openid-connect/token";

        var restClient = org.springframework.web.client.RestClient.create();
        String body = restClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials"
                        + "&client_id=" + clientId
                        + "&client_secret=" + clientSecret)
                .retrieve()
                .body(String.class);

        return com.jayway.jsonpath.JsonPath.read(body, "$.access_token");
    }
}
