package com.example.springapi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Issues and validates JSON Web Tokens (JWT) for stateless authentication.
 *
 * <h3>JWT basics</h3>
 * <p>A JWT is a compact, self-contained token:
 * <pre>
 * header.payload.signature
 * </pre>
 * The payload carries claims (subject, issued-at, expiration). The signature is a
 * cryptographic hash of header+payload using the server's secret key. Because the token
 * is self-contained, the server doesn't need a session store — it just verifies the
 * signature on every request.
 *
 * <h3>Algorithm</h3>
 * <p>HMAC-SHA256 ({@code HS256}) is used via {@code Keys.hmacShaKeyFor()}. The secret key
 * must be at least 256 bits (32 bytes) for HS256. It is injected from {@code jwt.secret}
 * in application configuration (production: env variable or Vault). A default development
 * key is provided for local runs only.
 *
 * <h3>Token lifecycle</h3>
 * <ul>
 *   <li>Created by {@link #generateToken} at login (valid for 24 hours).</li>
 *   <li>Verified by {@link #validateToken} on every protected request.</li>
 *   <li>Subject (username) extracted by {@link #getUsername} to populate the SecurityContext.</li>
 * </ul>
 *
 * <p>Library: JJWT (io.jsonwebtoken) 0.12.x — the fluent API changed significantly between
 * 0.11.x and 0.12.x; ensure the version in pom.xml matches the API used here.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** Token validity: 24 hours. Adjust for your security/UX requirements. */
    private static final long EXPIRATION_MS = 24L * 60 * 60 * 1000;

    /** HMAC-SHA256 key derived from the configured secret string. */
    private final SecretKey secretKey;

    /**
     * Derives the signing key from the configured secret.
     * {@code Keys.hmacShaKeyFor()} validates that the byte array is long enough for HS256
     * (≥32 bytes), throwing {@code WeakKeyException} at startup if the secret is too short.
     */
    public JwtTokenProvider(@Value("${jwt.secret:dev-secret-key-min-32-chars-long}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates a signed JWT for the given username.
     * The token contains: {@code sub} (username), {@code iat} (issued-at), {@code exp} (expiry).
     */
    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRATION_MS);

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Validates the token signature and expiry.
     * Returns {@code false} (instead of throwing) for any invalid token — expired,
     * malformed, wrong signature, or tampered payload.
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
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
     * Must only be called after {@link #validateToken} returns {@code true}.
     */
    public String getUsername(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }
}
