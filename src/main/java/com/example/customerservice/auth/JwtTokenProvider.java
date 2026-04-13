package com.example.customerservice.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private final SecretKey secretKey;
    private final RefreshTokenRepository refreshTokenRepository;

    public JwtTokenProvider(@Value("${jwt.secret:dev-secret-key-min-32-chars-long}") String secret,
                            RefreshTokenRepository refreshTokenRepository) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * Generates a signed access token JWT for the given username (15 min validity).
     */
    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ACCESS_TOKEN_EXPIRATION_MS);

        return Jwts.builder()
                .subject(username)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
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
        } catch (Exception e) {
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

    @Transactional
    public void deleteRefreshToken(RefreshToken token) {
        refreshTokenRepository.delete(token);
    }

    @Transactional
    public void deleteRefreshTokensByUsername(String username) {
        refreshTokenRepository.deleteByUsername(username);
    }
}
