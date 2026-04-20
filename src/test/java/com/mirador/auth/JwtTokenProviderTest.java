package com.mirador.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    // --- getRole ---

    @Test
    void getRole_extractsRoleClaim() {
        String token = provider.generateToken("alice", "ROLE_ADMIN");
        assertThat(provider.getRole(token)).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void getRole_fallsBackToReader_whenClaimMissing() {
        // Build a token WITHOUT the role claim using the same key — simulates a legacy
        // token that pre-dates the role claim introduction.
        SecretKey key = Keys.hmacShaKeyFor(
                "test-secret-key-minimum-32-characters!".getBytes(StandardCharsets.UTF_8));
        String legacy = Jwts.builder()
                .subject("alice")
                .issuer("customer-service")
                .audience().add("customer-service-api").and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();
        assertThat(provider.getRole(legacy)).isEqualTo("ROLE_READER");
    }

    // --- deprecated single-arg generateToken ---

    @Test
    void generateToken_deprecated_derivesAdminFromAdminUsername() {
        String token = provider.generateToken("admin");
        assertThat(provider.getRole(token)).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void generateToken_deprecated_derivesUserFromUserUsername() {
        String token = provider.generateToken("user");
        assertThat(provider.getRole(token)).isEqualTo("ROLE_USER");
    }

    @Test
    void generateToken_deprecated_derivesReaderFromOtherUsernames() {
        String token = provider.generateToken("random-person");
        assertThat(provider.getRole(token)).isEqualTo("ROLE_READER");
    }

    // --- refresh tokens (DB-backed, via mocked repository) ---

    @Test
    void generateRefreshToken_savesAndReturnsRandomUuid() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        JwtTokenProvider p = new JwtTokenProvider("test-secret-key-minimum-32-characters!", repo, null);

        String value = p.generateRefreshToken("alice");
        assertThat(value).isNotBlank();
        verify(repo).save(any(RefreshToken.class));
    }

    @Test
    void validateRefreshToken_returnsEntity_whenValid() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        RefreshToken stored = new RefreshToken();
        stored.setToken("opaque-value");
        stored.setUsername("alice");
        stored.setExpiryDate(Instant.now().plusSeconds(300));
        when(repo.findByToken("opaque-value")).thenReturn(Optional.of(stored));

        JwtTokenProvider p = new JwtTokenProvider("test-secret-key-minimum-32-characters!", repo, null);
        RefreshToken result = p.validateRefreshToken("opaque-value");
        assertThat(result).isSameAs(stored);
    }

    @Test
    void validateRefreshToken_throws_whenNotFound() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        when(repo.findByToken("missing")).thenReturn(Optional.empty());

        JwtTokenProvider p = new JwtTokenProvider("test-secret-key-minimum-32-characters!", repo, null);
        assertThatThrownBy(() -> p.validateRefreshToken("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid refresh token");
    }

    @Test
    void validateRefreshToken_deletesAndThrows_whenExpired() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        RefreshToken expired = new RefreshToken();
        expired.setToken("old");
        expired.setUsername("alice");
        expired.setExpiryDate(Instant.now().minusSeconds(60));
        when(repo.findByToken("old")).thenReturn(Optional.of(expired));

        JwtTokenProvider p = new JwtTokenProvider("test-secret-key-minimum-32-characters!", repo, null);
        assertThatThrownBy(() -> p.validateRefreshToken("old"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
        verify(repo).delete(expired);
    }

    @Test
    void deleteRefreshToken_delegatesToRepository() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        RefreshToken token = new RefreshToken();

        JwtTokenProvider p = new JwtTokenProvider("test-secret-key-minimum-32-characters!", repo, null);
        p.deleteRefreshToken(token);

        verify(repo).delete(token);
    }

    @Test
    void deleteRefreshTokensByUsername_delegatesToRepository() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        JwtTokenProvider p = new JwtTokenProvider("test-secret-key-minimum-32-characters!", repo, null);

        p.deleteRefreshTokensByUsername("alice");

        verify(repo).deleteByUsername("alice");
    }

    // --- blacklist (Redis-backed, null-safe when Redis is absent) ---

    @Test
    void blacklistToken_isNoOp_whenRedisIsNull() {
        // The no-Redis branch is the unit-test default — just prove it doesn't throw
        // or touch anything. The explicit assertThatCode keeps Sonar java:S2699 happy
        // (every JUnit test must carry at least one assertion).
        String token = provider.generateToken("alice", "ROLE_USER");
        assertThatCode(() -> provider.blacklistToken(token)).doesNotThrowAnyException();
    }

    @Test
    void isBlacklisted_returnsFalse_whenRedisIsNull() {
        assertThat(provider.isBlacklisted("any-token")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void blacklistToken_writesToRedis_withRemainingTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        JwtTokenProvider p = new JwtTokenProvider("test-secret-key-minimum-32-characters!", repo, redis);

        String token = p.generateToken("alice", "ROLE_USER");
        p.blacklistToken(token);

        // Verifies the full "hasKey present" branch and the TTL-positive branch.
        verify(ops).set(eq("jwt:blacklist:" + token), eq("1"), any(java.time.Duration.class));
    }

    @Test
    void isBlacklisted_delegatesToRedisHasKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(Boolean.TRUE);

        JwtTokenProvider p = new JwtTokenProvider("test-secret-key-minimum-32-characters!", null, redis);
        assertThat(p.isBlacklisted("some-token")).isTrue();
        verify(redis).hasKey("jwt:blacklist:some-token");
    }

    @Test
    void blacklistToken_swallowsExceptions_neverPropagates() {
        // Corrupted token → parseClaims throws → catch swallows. The call must not
        // leak the exception to the caller (security-relevant logging happens via SLF4J).
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        JwtTokenProvider p = new JwtTokenProvider("test-secret-key-minimum-32-characters!", null, redis);

        p.blacklistToken("not-a-jwt");

        // No Redis write should have occurred because the parse failed before the set call.
        verify(redis, never()).opsForValue();
    }
}
