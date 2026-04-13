package com.example.customerservice.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Authentication controller — issues and refreshes JWT tokens.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST /auth/login} — exchange credentials for a JWT</li>
 *   <li>{@code POST /auth/refresh} — exchange a valid JWT for a new one (extends session)</li>
 * </ul>
 *
 * <h3>Security features</h3>
 * <ul>
 *   <li>Brute-force protection: after 5 failed attempts, the IP is locked out for 15 minutes
 *       ({@link LoginAttemptService})</li>
 *   <li>Audit logging: all login attempts (success/failure) are logged with IP and username</li>
 *   <li>Remaining attempts count is returned on failure to aid legitimate users</li>
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
     * Validates credentials and returns a signed JWT.
     *
     * <p>Security flow:
     * <ol>
     *   <li>Check IP lockout (brute-force protection) → 429 if blocked</li>
     *   <li>Validate credentials → 401 with remaining attempts if wrong</li>
     *   <li>Issue JWT + audit log on success</li>
     * </ol>
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
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
        String token = jwtTokenProvider.generateToken(request.username());
        log.info("audit_login_success ip={} username={}", ip, request.username());
        return ResponseEntity.ok(Map.of("token", token));
    }

    /**
     * Refreshes a valid JWT — returns a new token with a fresh expiration.
     *
     * <p>The client sends the current (still valid) token in the {@code Authorization} header.
     * The server extracts the username and issues a new token with a fresh 24h expiry.
     * This allows sessions to be extended without re-entering credentials.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "Missing or invalid Authorization header"));
        }

        String token = authHeader.substring("Bearer ".length());
        if (!jwtTokenProvider.validateToken(token)) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or expired token"));
        }

        String username = jwtTokenProvider.getUsername(token);
        String newToken = jwtTokenProvider.generateToken(username);
        log.info("audit_token_refresh username={}", username);
        return ResponseEntity.ok(Map.of("token", newToken));
    }

    private String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record LoginRequest(String username, String password) {}
}
