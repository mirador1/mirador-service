package com.mirador.customer;
import com.mirador.AbstractIntegrationTest;

import com.mirador.customer.CustomerRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.ApiVersionInserter;

/**
 * Integration tests for the Customer API using Spring Framework 7's {@link RestTestClient}.
 *
 * <h3>RestTestClient vs MockMvc</h3>
 * <p>{@link RestTestClient} is the Spring Framework 7 fluent test DSL for servlet-stack
 * applications. It wraps {@link org.springframework.test.web.servlet.MockMvc} under the
 * hood but exposes an API modelled after {@code WebTestClient} (reactive stack), making
 * it familiar for teams using both stacks.
 *
 * <p>Key differences from {@code MockMvc}:
 * <ul>
 *   <li><b>Fluent chaining</b> — no checked {@code Exception} to declare.</li>
 *   <li><b>AssertJ-based assertions</b> — {@code expectStatus().isOk()} gives better
 *       failure messages than Hamcrest matchers.</li>
 *   <li><b>First-class API versioning</b> — {@code .apiVersion("2.0")} sets the configured
 *       version header/param ({@code X-API-Version} here) without knowing the header name.
 *       See {@code spring.mvc.apiversion.*} in {@code application.yml}.</li>
 *   <li><b>{@code .mutate()}</b> — fork a client with different defaults (e.g. a different
 *       auth token) without rebuilding the full context.</li>
 * </ul>
 *
 * <h3>Authentication strategy</h3>
 * <p>Unlike MockMvc which supports {@code .with(SecurityMockMvcRequestPostProcessors.user(...))}
 * to bypass the security filter chain, {@link RestTestClient} exercises the real
 * {@link com.mirador.auth.JwtAuthenticationFilter}. A JWT token is obtained
 * via {@code POST /auth/login} in {@link #setUp()} and set as the default
 * {@code Authorization} header on {@link #authenticatedClient}.
 *
 * <p>The existing {@link CustomerApiITest} (MockMvc) is kept alongside to show both styles.
 *
 * [Spring Framework 7 / Spring Boot 4] — {@link RestTestClient} and the {@code .apiVersion()}
 * method on the request spec are new in Spring Framework 7.0.
 */
@AutoConfigureMockMvc
class CustomerRestClientITest extends AbstractIntegrationTest {

    /**
     * Spring Boot auto-configures MockMvc with the full servlet filter chain (including
     * {@link com.mirador.observability.RequestIdFilter},
     * {@link com.mirador.resilience.RateLimitingFilter}, etc.) and Spring
     * Security. Injecting it here lets {@link RestTestClient#bindTo(MockMvc)} wrap the
     * already-configured instance rather than rebuilding it from scratch.
     */
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    /** Unauthenticated client — used to verify 401 responses. */
    private RestTestClient restClient;

    /** Client with {@code Authorization: Bearer <token>} set as a default header. */
    private RestTestClient authenticatedClient;

    @BeforeEach
    void setUp() {
        customerRepository.deleteAll();

        // Wrap the auto-configured MockMvc in RestTestClient.
        // ApiVersionInserter.useHeader("X-API-Version") tells the client how to
        // serialise .apiVersion("2.0") calls — mirrors the server-side configuration
        // in {@code spring.mvc.apiversion.use.header} which reads the version from that same header.
        restClient = RestTestClient
                .bindTo(mockMvc)
                .apiVersionInserter(ApiVersionInserter.useHeader("X-API-Version"))
                .build();

        // Obtain a real JWT token through the /auth/login endpoint and fork the client
        // with that token pre-set as the default Authorization header.
        // .mutate() returns a builder pre-seeded with all current settings.
        byte[] loginBody = restClient.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"username":"admin","password":"admin"}
                        """)
                .exchangeSuccessfully()
                .expectBody()
                .returnResult()
                .getResponseBody();

        String token = JsonPath.read(new String(loginBody), "$.accessToken");

        authenticatedClient = restClient.mutate()
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    /**
     * POST /customers → 200 with the created customer body.
     * Demonstrates: {@code .body(Object)}, {@code .expectBody().jsonPath()} assertions.
     */
    @Test
    void shouldCreateCustomer() {
        authenticatedClient.post()
                .uri("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"name":"Alice","email":"alice@example.com"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Alice")
                .jsonPath("$.email").isEqualTo("alice@example.com");
    }

    /**
     * POST then GET → the created record appears in the page.
     * Demonstrates: chaining two requests against the same context.
     */
    @Test
    void shouldListCreatedCustomers() {
        createCustomer("Bob", "bob@example.com");

        authenticatedClient.get()
                .uri("/customers")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].name").isEqualTo("Bob");
    }

    /**
     * GET /customers/{id} → 404 for an unknown ID.
     * Demonstrates: {@code .exchange()} allows asserting non-2xx status codes, whereas
     * {@code .exchangeSuccessfully()} would fail the test immediately.
     */
    @Test
    void shouldReturn404ForUnknownCustomer() {
        authenticatedClient.get()
                .uri("/customers/99999")
                .exchange()
                .expectStatus().isNotFound();
    }

    // -------------------------------------------------------------------------
    // API versioning — first-class .apiVersion() support              [Spring 7]
    // -------------------------------------------------------------------------

    /**
     * GET /customers with no API version → v1 response (no {@code createdAt} field).
     * The default version is 1.0 (configured in {@code spring.mvc.apiversion.*} in {@code application.yml}).
     */
    @Test
    void shouldReturnV1ShapeByDefault() {
        createCustomer("Carol", "carol@example.com");

        authenticatedClient.get()
                .uri("/customers")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].name").isEqualTo("Carol")
                .jsonPath("$.content[0].createdAt").doesNotExist();
    }

    /**
     * GET /customers with API version 2.0 → v2 DTO shape includes {@code createdAt}.
     *
     * <p>Demonstrates {@code .apiVersion("2.0")}: RestTestClient applies the configured
     * version strategy (here: {@code X-API-Version} header) without the caller knowing
     * the header name. The equivalent MockMvc call is {@code .header("X-API-Version", "2.0")}.
     *
     * [Spring Framework 7] — {@code .apiVersion()} on the request spec.
     */
    @Test
    void shouldReturnV2ShapeWithApiVersion() {
        createCustomer("Dave", "dave@example.com");

        authenticatedClient.get()
                .uri("/customers")
                .apiVersion("2.0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].name").isEqualTo("Dave")
                .jsonPath("$.content[0].createdAt").isNotEmpty();
    }

    /**
     * Demonstrate {@code .mutate()} to fork the client with a different default API version.
     * All requests on {@code v2Client} automatically use version 2.0.
     *
     * [Spring Framework 7] — {@code .defaultApiVersion()} on the builder via {@code .mutate()}.
     */
    @Test
    void shouldReturnV2ShapeViaDefaultApiVersion() {
        createCustomer("Eve", "eve@example.com");

        RestTestClient v2Client = authenticatedClient.mutate()
                .defaultApiVersion("2.0")
                .build();

        v2Client.get()
                .uri("/customers")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].createdAt").isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Security
    // -------------------------------------------------------------------------

    /**
     * GET /customers without authentication → 401.
     * Demonstrates asserting non-2xx responses with the unauthenticated client.
     */
    @Test
    void shouldReturn401WhenNotAuthenticated() {
        restClient.get()
                .uri("/customers")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // -------------------------------------------------------------------------
    // Headers
    // -------------------------------------------------------------------------

    /**
     * Every response carries an {@code X-Request-Id} set by the RequestIdFilter.
     * Demonstrates: {@code .expectHeader().exists()}.
     */
    @Test
    void shouldIncludeRequestIdHeader() {
        authenticatedClient.get()
                .uri("/customers")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id");
    }

    /**
     * A caller-supplied {@code X-Request-Id} is echoed back unchanged.
     * Demonstrates: {@code .expectHeader().valueEquals()}.
     */
    @Test
    void shouldEchoIncomingRequestId() {
        authenticatedClient.get()
                .uri("/customers")
                .header("X-Request-Id", "trace-abc-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "trace-abc-123");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void createCustomer(String name, String email) {
        authenticatedClient.post()
                .uri("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(String.format("""
                        {"name":"%s","email":"%s"}
                        """, name, email))
                .exchangeSuccessfully();
    }
}
