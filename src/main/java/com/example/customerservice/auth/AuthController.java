package com.example.customerservice.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <h3>Security features</h3>
 * <ul>
 *   <li>Brute-force protection: after 5 failed attempts, the IP is locked out for 15 minutes
 *       ({@link LoginAttemptService})</li>
 *   <li>Audit logging: all login attempts (success/failure) are logged with IP and username</li>
 *   <li>Remaining attempts count is returned on failure to aid legitimate users</li>
 *   <li>Input validation: {@code @NotBlank} + {@code @Size} on credentials</li>
 * </ul>
 *
 * <h3>Demo credentials</h3>
 * <p>{@code admin/admin} — hardcoded for demo purposes.
 * In production, replace with a {@code UserDetailsService} + BCrypt password hashing.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin";

    private final JwtTokenProvider jwtTokenProvider;
    private final LoginAttemptService loginAttemptService;

    public AuthController(JwtTokenProvider jwtTokenProvider, LoginAttemptService loginAttemptService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.loginAttemptService = loginAttemptService;
    }

    /**
     * Validates credentials and returns a signed JWT access + refresh token pair.
     *
     * <p>Security flow:
     * <ol>
     *   <li>Check IP lockout (brute-force protection) → 429 if blocked</li>
     *   <li>Validate credentials → 401 with remaining attempts if wrong</li>
     *   <li>Issue access + refresh tokens + audit log on success</li>
     * </ol>
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = extractIp(httpRequest);

        if (loginAttemptService.isBlocked(ip)) {
            log.warn("audit_login_blocked ip={} reason=brute_force_lockout", ip);
            return ResponseEntity.status(429)
                    .body(Map.of("error", "Too many failed attempts. Try again later.",
                                 "retryAfterMinutes", LoginAttemptService.LOCKOUT_MINUTES));
        }

        if (!ADMIN_USERNAME.equals(request.username()) || !ADMIN_PASSWORD.equals(request.password())) {
            loginAttemptService.recordFailure(ip);
            int remaining = loginAttemptService.getRemainingAttempts(ip);
            log.warn("audit_login_failed ip={} username={} remaining_attempts={}", ip, request.username(), remaining);
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid credentials",
                                 "remainingAttempts", remaining));
        }

        loginAttemptService.recordSuccess(ip);

        // Clean old refresh tokens for this user
        jwtTokenProvider.deleteRefreshTokensByUsername(request.username());

        String accessToken = jwtTokenProvider.generateToken(request.username());
        String refreshToken = jwtTokenProvider.generateRefreshToken(request.username());
        log.info("audit_login_success ip={} username={}", ip, request.username());
        return ResponseEntity.ok(Map.of("accessToken", accessToken, "refreshToken", refreshToken));
    }

    /**
     * Rotates the refresh token and returns a new access + refresh token pair.
     * The old refresh token is consumed (deleted) — single-use to prevent replay attacks.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest request) {
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

    private String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record LoginRequest(
            @NotBlank @Size(max = 50) String username,
            @NotBlank @Size(max = 128) String password) {}
    public record RefreshRequest(
            @NotBlank @Size(max = 256) String refreshToken) {}
}
