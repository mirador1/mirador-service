package com.mirador.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private final JwtTokenProvider provider =
            new JwtTokenProvider("test-secret-key-minimum-32-characters!", null, null);

    @Test
    void generateToken_thenValidateAndExtractUsername() {
        String token = provider.generateToken("alice", "ROLE_ADMIN");

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUsername(token)).isEqualTo("alice");
    }

    @Test
    void validateToken_returnsFalse_forTamperedToken() {
        String token = provider.generateToken("alice", "ROLE_ADMIN");
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    void validateToken_returnsFalse_forEmptyString() {
        assertThat(provider.validateToken("")).isFalse();
    }

    @Test
    void validateToken_returnsFalse_forTokenSignedWithDifferentKey() {
        JwtTokenProvider other = new JwtTokenProvider("other-secret-key-minimum-32-characters!", null, null);
        String foreignToken = other.generateToken("bob", "ROLE_USER");

        assertThat(provider.validateToken(foreignToken)).isFalse();
    }

    @Test
    void validateToken_returnsFalse_forExpiredToken() {
        // Build a token that expired 1 second ago using the same key
        SecretKey key = Keys.hmacShaKeyFor(
                "test-secret-key-minimum-32-characters!".getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        String expiredToken = Jwts.builder()
                .subject("alice")
                .issuer("customer-service")
                .audience().add("customer-service-api").and()
                .issuedAt(new Date(now - 2000))
                .expiration(new Date(now - 1000))
                .signWith(key)
                .compact();

        assertThat(provider.validateToken(expiredToken)).isFalse();
    }

    @Test
    void validateToken_returnsFalse_forNullToken() {
        assertThat(provider.validateToken(null)).isFalse();
    }

    @Test
    void validateToken_returnsFalse_forBlankToken() {
        assertThat(provider.validateToken("   ")).isFalse();
    }
}
