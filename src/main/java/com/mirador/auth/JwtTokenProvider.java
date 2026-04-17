package com.mirador.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates JSON Web Tokens (JWT) for stateless authentication.
 *
 * <h3>Token types</h3>
 * <ul>
 *   <li><b>Access token</b> — short-lived JWT (15 min) attached to every API request.</li>
 *   <li><b>Refresh token</b> — opaque UUID (7 days) stored in the database, used to
 *       obtain a new access token without re-entering credentials. Single-use: each
 *       refresh rotates the token (old deleted, new created).</li>
 * </ul>
 *
 * <h3>Algorithm</h3>
 * <p>HMAC-SHA256 ({@code HS256}) is used via {@code Keys.hmacShaKeyFor()}. The secret key
 * must be at least 256 bits (32 bytes) for HS256. It is injected from {@code jwt.secret}
 * in application configuration (production: env variable or Vault).
 *
 * <p>Library: JJWT (io.jsonwebtoken) 0.12.x — the fluent API changed significantly between
 * 0.11.x and 0.12.x; ensure the version in pom.xml matches the API used here.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** Access token validity: 15 minutes. */
    private static final long ACCESS_TOKEN_EXPIRATION_MS = 15L * 60 * 1000;

    /** Refresh token validity: 7 days. */
    private static final long REFRESH_TOKEN_EXPIRATION_MS = 7L * 24 * 60 * 60 * 1000;

    /** JWT issuer claim — identifies the service that issued the token. */
    private static final String ISSUER = "customer-service";

    /** JWT audience claim — tokens are only valid for this API. */
    private static final String AUDIENCE = "customer-service-api";

    /** Redis key prefix for blacklisted JWT access tokens. TTL = remaining token lifetime. */
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final SecretKey secretKey;
    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Optional: {@code null} in unit tests where no Redis context is available.
     * Injected via the all-args constructor with {@code required = false} instead of
     * field injection (Sonar S6813) so the field can remain {@code final}.
     */
    private final StringRedisTemplate redisTemplate;

    public JwtTokenProvider(@Value("${jwt.secret:dev-secret-key-min-32-chars-long}") String secret,
                            RefreshTokenRepository refreshTokenRepository,
                            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.refreshTokenRepository = refreshTokenRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Blacklists a JWT access token for its remaining validity duration.
     * Stores the token hash in Redis with TTL = remaining seconds until expiry.
     * Subsequent requests carrying this token will be rejected by {@link JwtAuthenticationFilter}.
     */
    public void blacklistToken(String token) {
        if (redisTemplate == null) return;  // no-op in unit test context
        try {
            Claims claims = parseClaims(token);
            long ttlMs = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (ttlMs > 0) {
                redisTemplate.opsForValue().set(
                        BLACKLIST_PREFIX + token,
                        "1",
                        java.time.Duration.ofMillis(ttlMs));
            }
        } catch (Exception e) {
            // WARN because a blacklist failure means a logged-out token could still be accepted
            // until it naturally expires — a security-relevant event worth surfacing in logs.
            log.warn("blacklist_failed — token will expire naturally: {}", e.getMessage());
        }
    }

    /** Returns true if the token has been blacklisted (e.g., via logout). */
    public boolean isBlacklisted(String token) {
        if (redisTemplate == null) return false;  // no-op in unit test context
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
    }

    /** Parses JWT claims — shared by {@link #blacklistToken} and {@link #isBlacklisted}. */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Generates a signed access token JWT for the given username and role (15 min validity).
     *
     * <p>The role is passed in explicitly from the caller (loaded from the database via
     * {@link AppUserDetailsService}) instead of being derived from the username. This decouples
     * token issuance from username conventions and allows role changes to take effect on next login.
     *
     * <p>The {@code role} claim is embedded in the token so clients (Angular JWT inspector,
     * Swagger UI) can display the role without a separate API call.
     *
     * @param username the authenticated user's username ({@code sub} claim)
     * @param role     the Spring Security role string, e.g. {@code ROLE_ADMIN}
     */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ACCESS_TOKEN_EXPIRATION_MS);
        return Jwts.builder()
                .subject(username)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Convenience overload that derives the role from the username for backwards compatibility.
     * Prefer {@link #generateToken(String, String)} when the role is already known (e.g. from DB).
     *
     * @deprecated since 1.5.0 — Use {@link #generateToken(String, String)}; the role
     *     must come from the database, not from the username shape. Slated for removal
     *     in 2.0 once all in-tree callers have migrated (see JwtTokenProviderTest).
     */
    @Deprecated(since = "1.5.0", forRemoval = true)
    @SuppressWarnings("java:S1133") // scheduled removal is tracked; see @Deprecated#forRemoval=true
    public String generateToken(String username) {
        // Legacy mapping: derive role from well-known username strings
        String role = switch (username) {
            case "admin" -> "ROLE_ADMIN";
            case "user"  -> "ROLE_USER";
            default      -> "ROLE_READER";
        };
        return generateToken(username, role);
    }

    /**
     * Extracts the {@code role} claim from a previously validated access token.
     * Returns {@code "ROLE_READER"} as a safe default if the claim is absent.
     */
    public String getRole(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        // Default to ROLE_READER for tokens that pre-date this claim (safe fallback)
        return claims.getOrDefault("role", "ROLE_READER").toString();
    }

    /**
     * Creates an opaque refresh token (UUID), persists it in the database, and returns its value.
     * The token is single-use — it must be deleted and replaced on each refresh.
     */
    @Transactional
    public String generateRefreshToken(String username) {
        var token = new RefreshToken();
        token.setToken(UUID.randomUUID().toString());
        token.setUsername(username);
        token.setExpiryDate(Instant.now().plusMillis(REFRESH_TOKEN_EXPIRATION_MS));
        refreshTokenRepository.save(token);
        return token.getToken();
    }

    /**
     * Validates the token signature, expiry, issuer, and audience.
     * Returns {@code false} (instead of throwing) for any invalid token.
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException covers: expired, malformed, wrong signature, missing/incorrect claims.
            // IllegalArgumentException is thrown by JJWT when the token string is null or blank.
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extracts the username ({@code sub} claim) from a previously validated token.
     */
    public String getUsername(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    /**
     * Validates a refresh token: checks existence in the database and expiry.
     * Throws {@link IllegalArgumentException} if the token is invalid or expired.
     */
    @Transactional
    public RefreshToken validateRefreshToken(String token) {
        var refreshToken = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));
        if (refreshToken.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new IllegalArgumentException("Refresh token expired");
        }
        return refreshToken;
    }

    /**
     * Deletes a single refresh token — called after a successful token rotation
     * (the old token is deleted; {@link #generateRefreshToken} creates a new one).
     *
     * @implNote Called within the same transaction as {@link #generateRefreshToken} to
     *           ensure the old token disappears atomically with the new one.
     */
    @Transactional
    public void deleteRefreshToken(RefreshToken token) {
        refreshTokenRepository.delete(token);
    }

    /**
     * Deletes all refresh tokens for a user — used on logout-all-devices.
     * After this call, existing refresh tokens from any device will be rejected
     * by {@link #validateRefreshToken}, forcing re-authentication everywhere.
     *
     * @apiNote The corresponding access tokens (JWTs) continue to be valid until they
     *          expire (max 15 min). Use {@link #blacklistToken} to invalidate them immediately
     *          if stronger security is required.
     */
    @Transactional
    public void deleteRefreshTokensByUsername(String username) {
        refreshTokenRepository.deleteByUsername(username);
    }
}
