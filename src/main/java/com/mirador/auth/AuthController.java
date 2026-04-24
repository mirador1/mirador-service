package com.mirador.auth;

import com.mirador.observability.port.AuditEventPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * <h3>Demo credentials (three-tier role model)</h3>
 * <ul>
 *   <li>{@code admin / admin} — {@code ROLE_ADMIN}: full access (read, write, delete)</li>
 *   <li>{@code user / user}   — {@code ROLE_USER}: read + write (no delete)</li>
 *   <li>{@code viewer / viewer} — {@code ROLE_READER}: read-only</li>
 * </ul>
 * <p>In production, replace with a {@code UserDetailsService} + BCrypt password hashing.
 */
@Tag(name = "Authentication", description = "JWT login and token refresh. Demo credentials: admin/admin | user/user | viewer/viewer")
@SecurityRequirements   // these endpoints are permit-all, no JWT required
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    // Sonar java:S1192 — "error" key appears in multiple response maps.
    private static final String KEY_ERROR = "error";

    private final JwtTokenProvider jwtTokenProvider;
    private final LoginAttemptService loginAttemptService;
    /**
     * Write-side audit port — domain interface, zero framework coupling.
     * Resolved by Spring to {@code AuditService} in production. See
     * {@link com.mirador.observability.port.AuditEventPort}.
     */
    private final AuditEventPort auditEventPort;
    /** Database-backed user store — replaces the previous hardcoded in-memory map. */
    private final AppUserDetailsService userDetailsService;
    /** BCrypt encoder used to verify the submitted password against the stored hash. */
    private final PasswordEncoder passwordEncoder;

    public AuthController(JwtTokenProvider jwtTokenProvider, LoginAttemptService loginAttemptService,
                          AuditEventPort auditEventPort, AppUserDetailsService userDetailsService,
                          PasswordEncoder passwordEncoder) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.loginAttemptService = loginAttemptService;
        this.auditEventPort = auditEventPort;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
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
    @Operation(summary = "Login — obtain access + refresh tokens",
            description = "Validates credentials and returns a signed JWT pair. "
                    + "Demo accounts: `admin/admin` (ROLE_ADMIN — full access), "
                    + "`user/user` (ROLE_USER — read + write), "
                    + "`viewer/viewer` (ROLE_READER — read-only). "
                    + "Brute-force protection: after 5 failed attempts the IP is locked out for 15 minutes. "
                    + "Copy the `accessToken` and use it in the **Authorize** button above.")
    @ApiResponse(responseCode = "200", description = "Login successful — `{accessToken, refreshToken}`")
    @ApiResponse(responseCode = "401", description = "Invalid credentials — includes `remainingAttempts`", content = @Content)
    @ApiResponse(responseCode = "429", description = "IP locked out after too many failures", content = @Content)
    @PostMapping("/login")
    public ResponseEntity<Object> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = extractIp(httpRequest);

        if (loginAttemptService.isBlocked(ip)) {
            log.warn("audit_login_blocked ip={} reason=brute_force_lockout", ip);
            auditEventPort.recordEvent(request.username(), "LOGIN_BLOCKED", "Brute-force lockout", ip);
            return ResponseEntity.status(429)
                    .body(Map.of(KEY_ERROR, "Too many failed attempts. Try again later.",
                                 "retryAfterMinutes", LoginAttemptService.LOCKOUT_MINUTES));
        }

        // Load user from the database (replaces the previous hardcoded in-memory map)
        UserDetails userDetails;
        try {
            userDetails = userDetailsService.loadUserByUsername(request.username());
        } catch (UsernameNotFoundException ignored) {
            loginAttemptService.recordFailure(ip);
            int remaining = loginAttemptService.getRemainingAttempts(ip);
            log.warn("audit_login_failed ip={} username={} remaining_attempts={}", ip, request.username(), remaining);
            auditEventPort.recordEvent(request.username(), "LOGIN_FAILED",
                    "User not found, " + remaining + " attempts remaining", ip);
            return ResponseEntity.status(401)
                    .body(Map.of(KEY_ERROR, "Invalid credentials", "remainingAttempts", remaining));
        }
        // Verify submitted password against the stored BCrypt hash
        if (!passwordEncoder.matches(request.password(), userDetails.getPassword())) {
            loginAttemptService.recordFailure(ip);
            int remaining = loginAttemptService.getRemainingAttempts(ip);
            log.warn("audit_login_failed ip={} username={} remaining_attempts={}", ip, request.username(), remaining);
            auditEventPort.recordEvent(request.username(), "LOGIN_FAILED",
                    "Invalid credentials, " + remaining + " attempts remaining", ip);
            return ResponseEntity.status(401)
                    .body(Map.of(KEY_ERROR, "Invalid credentials",
                                 "remainingAttempts", remaining));
        }

        loginAttemptService.recordSuccess(ip);

        // Extract the role from the user's granted authorities (ROLE_ADMIN / ROLE_USER / ROLE_READER)
        String role = userDetails.getAuthorities().iterator().next().getAuthority();

        // Clean old refresh tokens for this user
        jwtTokenProvider.deleteRefreshTokensByUsername(request.username());

        // Pass role explicitly — JwtTokenProvider no longer derives role from username
        String accessToken = jwtTokenProvider.generateToken(request.username(), role);
        String refreshToken = jwtTokenProvider.generateRefreshToken(request.username());
        log.info("audit_login_success ip={} username={}", ip, request.username());
        auditEventPort.recordEvent(request.username(), "LOGIN_SUCCESS", "JWT issued", ip);
        return ResponseEntity.ok(Map.of("accessToken", accessToken, "refreshToken", refreshToken));
    }

    /**
     * Rotates the refresh token and returns a new access + refresh token pair.
     * The old refresh token is consumed (deleted) — single-use to prevent replay attacks.
     */
    @Operation(summary = "Refresh tokens",
            description = "Rotates the refresh token and returns a new access + refresh pair. "
                    + "The old refresh token is consumed (single-use) to prevent replay attacks.")
    @ApiResponse(responseCode = "200", description = "New `{accessToken, refreshToken}`")
    @ApiResponse(responseCode = "401", description = "Refresh token expired or invalid", content = @Content)
    @PostMapping("/refresh")
    public ResponseEntity<Object> refresh(@Valid @RequestBody RefreshRequest request) {
        try {
            var oldToken = jwtTokenProvider.validateRefreshToken(request.refreshToken());
            String username = oldToken.getUsername();

            // Rotate: delete old, create new
            jwtTokenProvider.deleteRefreshToken(oldToken);
            // Look up the current role from the database — role may have changed since last login
            String role = userDetailsService.loadUserByUsername(username)
                    .getAuthorities().iterator().next().getAuthority();
            String accessToken = jwtTokenProvider.generateToken(username, role);
            String refreshToken = jwtTokenProvider.generateRefreshToken(username);
            log.info("audit_token_refresh username={}", username);
            auditEventPort.recordEvent(username, "TOKEN_REFRESH", "Refresh token rotated", null);
            return ResponseEntity.ok(Map.of("accessToken", accessToken, "refreshToken", refreshToken));
        } catch (IllegalArgumentException e) {
            // Log at warn — a refresh failure is not routine but not a server fault.
            // Reasons include: expired refresh token, replayed (already-deleted) token,
            // forged token that failed signature validation. The message is curated by
            // JwtTokenProvider so it is safe to log verbatim.
            log.warn("audit_token_refresh_failed reason={}", e.getMessage());
            return ResponseEntity.status(401).body(Map.of(KEY_ERROR, e.getMessage()));
        }
    }

    /**
     * Logs out the authenticated user by:
     * <ol>
     *   <li>Blacklisting the current access token in Redis (TTL = remaining expiry)</li>
     *   <li>Deleting all refresh tokens for the user from the database</li>
     * </ol>
     *
     * <p>After logout, the access token is rejected by {@link JwtAuthenticationFilter}
     * even if it hasn't expired yet. Requires a valid Bearer token in the Authorization header.
     */
    @Operation(summary = "Logout — invalidate access and refresh tokens",
            description = "Blacklists the current JWT (stored in Redis until expiry) and deletes all refresh tokens. "
                    + "Requires a valid Bearer token in the Authorization header.")
    @ApiResponse(responseCode = "200", description = "Logged out successfully")
    @PostMapping("/logout")
    public ResponseEntity<Object> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @AuthenticationPrincipal UserDetails principal) {
        // Blacklist the access token so it's rejected before its natural expiry
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            jwtTokenProvider.blacklistToken(token);
        }
        if (principal != null) {
            jwtTokenProvider.deleteRefreshTokensByUsername(principal.getUsername());
            auditEventPort.recordEvent(principal.getUsername(), "LOGOUT", "JWT blacklisted, refresh tokens deleted", null);
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
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
