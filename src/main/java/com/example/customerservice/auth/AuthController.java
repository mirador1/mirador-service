package com.example.customerservice.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Authentication controller — issues JWT access + refresh tokens in exchange for credentials.
 *
 * <h3>Token flow</h3>
 * <ol>
 *   <li>{@code POST /auth/login} — validates credentials, returns {@code {accessToken, refreshToken}}</li>
 *   <li>Client attaches the access token: {@code Authorization: Bearer <accessToken>}</li>
 *   <li>On 401 (access token expired), client calls {@code POST /auth/refresh} with the refresh token</li>
 *   <li>Server rotates the refresh token (old deleted, new created) and returns a fresh pair</li>
 * </ol>
 *
 * <h3>Demo credentials</h3>
 * <p>The username/password ({@code admin}/{@code admin}) are hardcoded for demo purposes.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin";

    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (!ADMIN_USERNAME.equals(request.username()) || !ADMIN_PASSWORD.equals(request.password())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        // Clean old refresh tokens for this user
        jwtTokenProvider.deleteRefreshTokensByUsername(request.username());

        String accessToken = jwtTokenProvider.generateToken(request.username());
        String refreshToken = jwtTokenProvider.generateRefreshToken(request.username());
        return ResponseEntity.ok(Map.of("accessToken", accessToken, "refreshToken", refreshToken));
    }

    /**
     * Rotates the refresh token and returns a new access + refresh token pair.
     * The old refresh token is consumed (deleted) — single-use to prevent replay attacks.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        try {
            var oldToken = jwtTokenProvider.validateRefreshToken(request.refreshToken());
            String username = oldToken.getUsername();

            // Rotate: delete old, create new
            jwtTokenProvider.deleteRefreshToken(oldToken);
            String accessToken = jwtTokenProvider.generateToken(username);
            String refreshToken = jwtTokenProvider.generateRefreshToken(username);
            return ResponseEntity.ok(Map.of("accessToken", accessToken, "refreshToken", refreshToken));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    public record LoginRequest(String username, String password) {}
    public record RefreshRequest(String refreshToken) {}
}
