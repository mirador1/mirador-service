package com.mirador.auth;

import com.mirador.AbstractIntegrationTest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Auth0 production JWT validation path.
 *
 * <h3>Why this class exists</h3>
 * <p>{@link KeycloakConfig#jwtDecoder()} branches on whether the configured
 * {@code keycloak.issuer-uri} contains {@code /realms/}. If it does, it treats the
 * issuer as Keycloak (JWKS at {@code /protocol/openid-connect/certs}, no audience
 * validation). Otherwise it treats it as an Auth0-style OIDC issuer (JWKS at
 * {@code /.well-known/jwks.json}, audience validation via {@code auth0.audience}).
 *
 * <p>The Auth0 branch is what actually runs in production (per the main
 * {@code application.yml} + ops docs — Auth0 replaces self-hosted Keycloak on GKE),
 * yet no {@code Auth0*} test existed under {@code src/test/}. Any regression in the
 * audience check or the Auth0 role-claim extraction
 * ({@link JwtAuthenticationFilter#authenticateKeycloak(String)} strategy 2 + 3) would
 * ship silently. Covering it costs one small ITest — worth it.
 *
 * <h3>Stubbing strategy</h3>
 * <p>Spinning up real Auth0 in a test is not an option (no tenant). Instead:
 * <ol>
 *   <li>Generate a fresh RSA 2048 keypair once per JVM ({@code @BeforeAll}).</li>
 *   <li>Start a JDK-built-in {@link HttpServer} on a free port that serves the
 *       public key as a JWKS document at {@code /.well-known/jwks.json}. The server
 *       has no framework dependency, so the test runs in milliseconds after the
 *       shared Spring context boots.</li>
 *   <li>Point {@code keycloak.issuer-uri} at {@code http://localhost:<port>/}
 *       (Auth0 style: no {@code /realms/} segment, trailing slash so the decoder
 *       appends {@code .well-known/jwks.json} cleanly) and
 *       {@code auth0.audience} at a fixed API identifier — these dynamic
 *       properties drive {@link KeycloakConfig} down the Auth0 branch.</li>
 *   <li>Each test mints a signed JWT with {@link SignedJWT} + {@link RSASSASigner},
 *       attaches it to a MockMvc request, and asserts the expected 200/401.</li>
 * </ol>
 *
 * <p>This avoids WireMock (not on the classpath), avoids Testcontainers-based
 * OIDC providers (heavy, memory-constrained per ADR-0034), and keeps the filter
 * code path end-to-end real — the exact {@link org.springframework.security.oauth2.jwt.NimbusJwtDecoder}
 * that runs in production performs the signature + claim checks against our stub.
 *
 * <h3>Why @Tag("auth0")</h3>
 * <p>Symmetric with {@code @Tag("keycloak-heavy")} for KeycloakAuthITest. Auth0
 * ITs are lightweight (no external container, ~1-2 s), so they are NOT excluded
 * by default — the tag is there for future selective runs (e.g. a CI job that
 * deliberately exercises only the Auth0 path after a Spring Security upgrade).
 */
@AutoConfigureMockMvc
@Tag("auth0")
class Auth0JwtValidationITest extends AbstractIntegrationTest {

    // Auth0 audience registered with the tenant — must match Spring's
    // `auth0.audience` property for the audience validator to accept the token.
    // The value is intentionally not a real customer API to avoid collision
    // with any production config leaking into tests.
    private static final String AUDIENCE = "https://mirador-api.test";

    // Auth0 roles live under a custom namespace claim per
    // JwtAuthenticationFilter Strategy 2. Keep this in sync with that file.
    private static final String ROLES_CLAIM = "https://mirador-api/roles";

    private static HttpServer jwksServer;
    private static RSAKey rsaJwk;           // full keypair (private + public) for signing
    private static String issuerUri;        // http://localhost:<port>/ — note trailing slash

    @Autowired
    MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Lifecycle: start the JWKS stub ONCE per JVM, before Spring context refresh.
    // -------------------------------------------------------------------------

    @BeforeAll
    static void startJwksServer() throws Exception {
        // Generate a fresh keypair per JVM. 2048 bit is the Auth0 default for
        // RS256 — matches the signing algorithm used by production tokens.
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();

        rsaJwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("auth0-test-kid")                  // kid must match the JWT header
                .algorithm(JWSAlgorithm.RS256)
                .build();

        // JWKSet#toPublicJWKSet strips the private half — the JWKS served to
        // the decoder must only expose the public key, same as a real Auth0
        // tenant. Leaking the private key would defeat the point of RS256.
        String jwksJson = new JWKSet(rsaJwk).toPublicJWKSet().toString();

        // Port 0 = ask the OS for a free ephemeral port. Avoids flakes when
        // multiple test JVMs (forkCount>1 in Surefire) run on the same host.
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext("/.well-known/jwks.json", exchange -> {
            byte[] body = jwksJson.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        jwksServer.start();

        int port = jwksServer.getAddress().getPort();
        // Trailing slash matters: NimbusJwtDecoder concatenates the issuer +
        // ".well-known/jwks.json" in KeycloakConfig's non-Keycloak branch.
        // Without the slash, JWKS would be requested at ".well-known/..." with
        // no host (URL parser error).
        issuerUri = "http://localhost:" + port + "/";
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) {
            // Immediate stop (0s grace) — no in-flight requests expected at
            // class teardown, and keeping the port alive blocks CI reruns.
            jwksServer.stop(0);
        }
    }

    // -------------------------------------------------------------------------
    // Spring property wiring — runs before context refresh, so the
    // @Bean JwtDecoder in KeycloakConfig is built against our stub.
    // -------------------------------------------------------------------------

    @DynamicPropertySource
    static void auth0Properties(DynamicPropertyRegistry registry) {
        // Drive KeycloakConfig down the Auth0 branch (no "/realms/" substring).
        registry.add("keycloak.issuer-uri", () -> issuerUri);
        registry.add("auth0.audience", () -> AUDIENCE);
    }

    // -------------------------------------------------------------------------
    // Happy path: valid Auth0 JWT → 200 and SecurityContext is populated with
    //             the namespaced role claim.
    // -------------------------------------------------------------------------

    @Test
    void validAuth0Jwt_withRolesClaim_grantsAccess() throws Exception {
        String token = mintJwt(builder -> builder
                .issuer(issuerUri)
                .audience(AUDIENCE)
                .subject("auth0|abc123")
                .claim(ROLES_CLAIM, List.of("ROLE_ADMIN")));

        mockMvc.perform(get("/customers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Expired JWT → 401. Guards the `exp` claim validator.
    // -------------------------------------------------------------------------

    @Test
    void expiredAuth0Jwt_returns401() throws Exception {
        Instant yesterday = Instant.now().minusSeconds(60 * 60 * 24);
        String token = mintJwt(builder -> builder
                .issuer(issuerUri)
                .audience(AUDIENCE)
                .subject("auth0|abc123")
                .issueTime(Date.from(yesterday.minusSeconds(3600)))
                .expirationTime(Date.from(yesterday))
                .claim(ROLES_CLAIM, List.of("ROLE_ADMIN")));

        mockMvc.perform(get("/customers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Wrong issuer → 401. Guards the issuer validator (createDefaultWithIssuer).
    // -------------------------------------------------------------------------

    @Test
    void wrongIssuerAuth0Jwt_returns401() throws Exception {
        String token = mintJwt(builder -> builder
                .issuer("https://not-our-tenant.auth0.com/")
                .audience(AUDIENCE)
                .subject("auth0|abc123")
                .claim(ROLES_CLAIM, List.of("ROLE_ADMIN")));

        mockMvc.perform(get("/customers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Wrong audience → 401. This is the check that stops tokens issued for a
    // DIFFERENT API in the same Auth0 tenant from being accepted here. It is
    // the single most security-sensitive test in this class.
    // -------------------------------------------------------------------------

    @Test
    void wrongAudienceAuth0Jwt_returns401() throws Exception {
        String token = mintJwt(builder -> builder
                .issuer(issuerUri)
                .audience("https://some-other-api.test")
                .subject("auth0|abc123")
                .claim(ROLES_CLAIM, List.of("ROLE_ADMIN")));

        mockMvc.perform(get("/customers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Helper: mint a fresh signed JWT with the supplied claims. Defaults for
    // iat/exp are set when the caller doesn't override them, so the three
    // tests above stay focused on the single claim they're actually probing.
    // -------------------------------------------------------------------------

    private static String mintJwt(java.util.function.Consumer<JWTClaimsSet.Builder> customiser)
            throws JOSEException {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        customiser.accept(builder);

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(rsaJwk.getKeyID())
                        .build(),
                builder.build());
        signedJWT.sign(new RSASSASigner(rsaJwk.toRSAPrivateKey()));
        return signedJWT.serialize();
    }
}
