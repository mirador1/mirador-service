package com.mirador.auth;

import com.mirador.observability.port.AuditEventPort;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthController} — covers the brute-force lockout
 * gate, credential validation, and the IP extraction (X-Forwarded-For
 * priority over remoteAddr).
 *
 * <p>Refresh + logout flows are exercised by the existing integration
 * tests; this file focuses on login because it has the most branching
 * (lockout / unknown user / wrong password / success) and the most
 * security-critical regressions.
 */
class AuthControllerTest {

    private JwtTokenProvider jwt;
    private LoginAttemptService loginAttempts;
    private AuditEventPort auditEventPort;
    private AppUserDetailsService userDetailsService;
    private PasswordEncoder passwordEncoder;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        jwt = mock(JwtTokenProvider.class);
        loginAttempts = mock(LoginAttemptService.class);
        auditEventPort = mock(AuditEventPort.class);
        userDetailsService = mock(AppUserDetailsService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        controller = new AuthController(jwt, loginAttempts, auditEventPort,
                userDetailsService, passwordEncoder);
    }

    private static AuthController.LoginRequest req(String username, String password) {
        return new AuthController.LoginRequest(username, password);
    }

    private static UserDetails sampleUser(String username, String role) {
        List<GrantedAuthority> auths = List.of(new SimpleGrantedAuthority(role));
        return new User(username, "$2a$10$hashedpassword", true,
                true, true, true, auths);
    }

    private static HttpServletRequest mockRequest(String remoteAddr, String xForwardedFor) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn(remoteAddr);
        when(req.getHeader("X-Forwarded-For")).thenReturn(xForwardedFor);
        return req;
    }

    // ── Brute-force lockout ────────────────────────────────────────────────────

    @Test
    void login_ipBlocked_returns429AndDoesNotEvenLookupUser() {
        when(loginAttempts.isBlocked("1.2.3.4")).thenReturn(true);

        ResponseEntity<Object> response = controller.login(
                req("admin", "admin"), mockRequest("1.2.3.4", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("error").containsKey("retryAfterMinutes");
        // Critical: blocked path must short-circuit BEFORE userDetailsService is
        // touched — otherwise an attacker could probe usernames during lockout.
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        verify(auditEventPort).recordEvent("admin", "LOGIN_BLOCKED",
                "Brute-force lockout", "1.2.3.4");
    }

    // ── Unknown user ──────────────────────────────────────────────────────────

    @Test
    void login_unknownUser_returns401WithRemainingAttempts() {
        when(loginAttempts.isBlocked(anyString())).thenReturn(false);
        when(userDetailsService.loadUserByUsername("ghost"))
                .thenThrow(new UsernameNotFoundException("not found"));
        when(loginAttempts.getRemainingAttempts("1.2.3.4")).thenReturn(4);

        ResponseEntity<Object> response = controller.login(
                req("ghost", "pwd"), mockRequest("1.2.3.4", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "Invalid credentials")
                .containsEntry("remainingAttempts", 4);
        verify(loginAttempts).recordFailure("1.2.3.4");
        verify(auditEventPort).recordEvent(eq("ghost"), eq("LOGIN_FAILED"),
                anyString(), eq("1.2.3.4"));
    }

    // ── Wrong password ────────────────────────────────────────────────────────

    @Test
    void login_wrongPassword_returns401AndRecordsFailure() {
        when(loginAttempts.isBlocked(anyString())).thenReturn(false);
        when(userDetailsService.loadUserByUsername("admin"))
                .thenReturn(sampleUser("admin", "ROLE_ADMIN"));
        when(passwordEncoder.matches("wrong", "$2a$10$hashedpassword")).thenReturn(false);
        when(loginAttempts.getRemainingAttempts("1.2.3.4")).thenReturn(3);

        ResponseEntity<Object> response = controller.login(
                req("admin", "wrong"), mockRequest("1.2.3.4", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("remainingAttempts", 3);
        verify(loginAttempts).recordFailure("1.2.3.4");
        // Critical: wrong-password path must NOT issue tokens, even if the
        // user is found. Pin the no-tokens-on-failure invariant.
        verify(jwt, never()).generateToken(anyString(), anyString());
        verify(jwt, never()).generateRefreshToken(anyString());
    }

    // ── Successful login ──────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returnsAccessAndRefreshTokens() {
        when(loginAttempts.isBlocked(anyString())).thenReturn(false);
        when(userDetailsService.loadUserByUsername("admin"))
                .thenReturn(sampleUser("admin", "ROLE_ADMIN"));
        when(passwordEncoder.matches("admin", "$2a$10$hashedpassword")).thenReturn(true);
        when(jwt.generateToken("admin", "ROLE_ADMIN")).thenReturn("jwt-access");
        when(jwt.generateRefreshToken("admin")).thenReturn("jwt-refresh");

        ResponseEntity<Object> response = controller.login(
                req("admin", "admin"), mockRequest("1.2.3.4", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("accessToken", "jwt-access")
                .containsEntry("refreshToken", "jwt-refresh");
        verify(loginAttempts).recordSuccess("1.2.3.4");
        // Old refresh tokens for this user are cleaned BEFORE issuing the
        // new pair — pinned to avoid the "user has 100 stale refresh
        // tokens after 100 logins" footprint.
        verify(jwt).deleteRefreshTokensByUsername("admin");
        verify(auditEventPort).recordEvent("admin", "LOGIN_SUCCESS",
                "JWT issued", "1.2.3.4");
    }

    // ── IP extraction (X-Forwarded-For priority) ──────────────────────────────

    @Test
    void login_xForwardedForHeader_takesPriorityOverRemoteAddr() {
        // Behind a load balancer the real client IP comes via X-Forwarded-For.
        // The lockout + audit must use the LB-forwarded IP, not the LB's own.
        when(loginAttempts.isBlocked("1.1.1.1")).thenReturn(true);

        ResponseEntity<Object> response = controller.login(
                req("admin", "admin"),
                mockRequest("10.0.0.1" /* LB IP, ignored */, "1.1.1.1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(loginAttempts).isBlocked("1.1.1.1");
        verify(loginAttempts, never()).isBlocked("10.0.0.1");
    }

    @Test
    void login_xForwardedForWithMultipleIps_takesFirstOne() {
        // Comma-separated chain: client, proxy1, proxy2 — first entry is
        // the closest-to-origin (the actual client).
        when(loginAttempts.isBlocked("1.1.1.1")).thenReturn(true);

        controller.login(req("admin", "admin"),
                mockRequest("10.0.0.1", "1.1.1.1, 10.0.0.2, 10.0.0.3"));

        verify(loginAttempts).isBlocked("1.1.1.1");
    }

    @Test
    void login_blankXForwardedFor_fallsBackToRemoteAddr() {
        when(loginAttempts.isBlocked("10.0.0.1")).thenReturn(true);

        controller.login(req("admin", "admin"),
                mockRequest("10.0.0.1", "  "));

        verify(loginAttempts).isBlocked("10.0.0.1");
    }
}
