package com.example.springapi;

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
 *   <li>A Keycloak-issued JWT for {@code ROLE_USER} grants read access ({@code GET /customers})</li>
 *   <li>A Keycloak-issued JWT for {@code ROLE_ADMIN} grants write access ({@code POST /customers})</li>
 *   <li>A Keycloak-issued JWT for {@code ROLE_USER} is denied write access ({@code POST /customers})</li>
 *   <li>A built-in JWT (from {@code POST /auth/login}) still works alongside Keycloak tokens</li>
 * </ul>
 *
 * <h3>Container setup</h3>
 * <p>A real Keycloak 26 instance is started via {@link KeycloakContainer} (Testcontainers).
 * The realm definition at {@code ops/keycloak/realm-demo.json} is imported at startup.
 * The container's issuer URI is injected into {@code keycloak.issuer-uri} so that
 * {@link com.example.springapi.auth.SecurityConfig} activates the OAuth2 resource server path.
 *
 * <h3>Token acquisition</h3>
 * <p>Tokens are obtained via Keycloak's Resource Owner Password Credentials (ROPC) grant
 * ({@code grant_type=password}) — suitable for testing but discouraged in production.
 * In production, use the Authorization Code + PKCE flow.
 *
 * [Spring Security / Spring Boot 4] — oauth2ResourceServer().jwt() dual-mode with custom JWT.
 * [Testcontainers] — KeycloakContainer from com.github.dasniko:testcontainers-keycloak.
 */
@AutoConfigureMockMvc
@Testcontainers
class KeycloakAuthITest extends AbstractIntegrationTest {

    /**
     * Keycloak container shared across all tests in this class.
     * The realm-demo.json is imported via {@code withRealmImportFile()}.
     *
     * <p>Using {@code @Container} at the static field level ensures the container is
     * started once per test class (rather than once per test method), which avoids
     * repeated 30-second container startup times.
     */
    @Container
    static final KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.2.5")
            .withRealmImportFile("realm-demo.json")
            // Disable HTTPS for local testing — Keycloak 26 requires HTTPS by default.
            // The env vars mirror the docker-compose.yml settings.
            .withEnv("KC_HTTP_ENABLED", "true")
            .withEnv("KC_HOSTNAME_STRICT", "false")
            .withEnv("KC_HOSTNAME_STRICT_HTTPS", "false");

    /**
     * Override {@code keycloak.issuer-uri} with the Testcontainers-assigned URL.
     * This activates the OAuth2 resource server path in
     * {@link com.example.springapi.auth.SecurityConfig}.
     *
     * <p>{@code keycloak.issuer-uri} is read by SecurityConfig via
     * {@code @Value("${keycloak.issuer-uri:}")}; when blank, the resource server is disabled.
     * Setting it here to the container's URL enables it for these tests only.
     */
    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("keycloak.issuer-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/spring-api-demo");
    }

    @Autowired
    MockMvc mockMvc;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void obtainTokens() {
        // Obtain tokens for the two pre-configured realm users (defined in realm-demo.json)
        userToken = obtainKeycloakToken("user", "user-password");
        adminToken = obtainKeycloakToken("admin", "admin-password");
    }

    // -------------------------------------------------------------------------
    // ROLE_USER — read access
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
        // Obtain a built-in JWT (not Keycloak)
        var loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"admin"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andReturn();

        String builtinToken = com.jayway.jsonpath.JsonPath.read(
                loginResult.getResponse().getContentAsString(), "$.token");

        mockMvc.perform(get("/customers")
                        .header("Authorization", "Bearer " + builtinToken))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Obtains a Keycloak access token for a realm user via the Resource Owner
     * Password Credentials (ROPC) grant.
     *
     * <p>The {@code spring-api} client is configured as {@code publicClient: true} and
     * {@code directAccessGrantsEnabled: true} in realm-demo.json to allow ROPC.
     */
    private String obtainKeycloakToken(String username, String password) {
        String tokenUrl = keycloak.getAuthServerUrl()
                + "/realms/spring-api-demo/protocol/openid-connect/token";

        var restClient = org.springframework.web.client.RestClient.create();
        String body = restClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=password"
                        + "&client_id=spring-api"
                        + "&username=" + username
                        + "&password=" + password)
                .retrieve()
                .body(String.class);

        return com.jayway.jsonpath.JsonPath.read(body, "$.access_token");
    }
}
