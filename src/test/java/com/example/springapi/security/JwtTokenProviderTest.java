package com.example.springapi.security;

import com.example.springapi.auth.JwtTokenProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private final JwtTokenProvider provider =
            new JwtTokenProvider("test-secret-key-minimum-32-characters!");

    @Test
    void generateToken_thenValidateAndExtractUsername() {
        String token = provider.generateToken("alice");

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUsername(token)).isEqualTo("alice");
    }

    @Test
    void validateToken_returnsFalse_forTamperedToken() {
        String token = provider.generateToken("alice");
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    void validateToken_returnsFalse_forEmptyString() {
        assertThat(provider.validateToken("")).isFalse();
    }

    @Test
    void validateToken_returnsFalse_forTokenSignedWithDifferentKey() {
        JwtTokenProvider other = new JwtTokenProvider("other-secret-key-minimum-32-characters!");
        String foreignToken = other.generateToken("bob");

        assertThat(provider.validateToken(foreignToken)).isFalse();
    }
}
