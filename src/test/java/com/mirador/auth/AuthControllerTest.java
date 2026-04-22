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

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Test
    void refresh_validRefreshToken_rotatesTokenAndReturnsNewPair() {
        var refreshReq = new AuthController.RefreshRequest("refresh-abc");
        var oldRefreshToken = new RefreshToken();
        oldRefreshToken.setUsername("alice");
        when(jwt.validateRefreshToken("refresh-abc")).thenReturn(oldRefreshToken);
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(sampleUser("alice", "ROLE_USER"));
        when(jwt.generateToken("alice", "ROLE_USER")).thenReturn("new-access");
        when(jwt.generateRefreshToken("alice")).thenReturn("new-refresh");

        ResponseEntity<Object> response = controller.refresh(refreshReq);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("accessToken", "new-access")
                .containsEntry("refreshToken", "new-refresh");
        // Critical: old refresh token MUST be deleted (single-use to prevent
        // replay attacks). Pinned because losing this delete would let an
        // attacker who captured a refresh token use it indefinitely.
        verify(jwt).deleteRefreshToken(oldRefreshToken);
        verify(auditEventPort).recordEvent("alice", "TOKEN_REFRESH",
                "Refresh token rotated", null);
    }

    @Test
    void refresh_invalidRefreshToken_returns401WithMessage() {
        var refreshReq = new AuthController.RefreshRequest("expired-or-fake");
        when(jwt.validateRefreshToken("expired-or-fake"))
                .thenThrow(new IllegalArgumentException("Refresh token expired"));

        ResponseEntity<Object> response = controller.refresh(refreshReq);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "Refresh token expired");
        // No new tokens issued on failure
        verify(jwt, never()).generateToken(anyString(), anyString());
    }

    @Test
    void refresh_picksUpRoleFromDbNotFromOldToken() {
        // Critical: role MUST come from the current DB state, not the old
        // token. Without this, a user demoted from ADMIN to USER would
        // keep their ADMIN access until they manually re-logged in.
        var refreshReq = new AuthController.RefreshRequest("refresh-xyz");
        var oldToken = new RefreshToken();
        oldToken.setUsername("bob");
        when(jwt.validateRefreshToken("refresh-xyz")).thenReturn(oldToken);
        // DB now says ROLE_READER (was ADMIN previously)
        when(userDetailsService.loadUserByUsername("bob"))
                .thenReturn(sampleUser("bob", "ROLE_READER"));
        when(jwt.generateToken("bob", "ROLE_READER")).thenReturn("new-access-with-reader");
        when(jwt.generateRefreshToken("bob")).thenReturn("new-refresh");

        controller.refresh(refreshReq);

        verify(jwt).generateToken("bob", "ROLE_READER");
        verify(jwt, never()).generateToken("bob", "ROLE_ADMIN");
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_blacklistsAccessTokenAndDeletesRefreshTokens() {
        var principal = sampleUser("alice", "ROLE_ADMIN");

        ResponseEntity<Object> response = controller.logout(
                "Bearer access-token-abc", principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jwt).blacklistToken("access-token-abc");
        verify(jwt).deleteRefreshTokensByUsername("alice");
        verify(auditEventPort).recordEvent("alice", "LOGOUT",
                "JWT blacklisted, refresh tokens deleted", null);
    }

    @Test
    void logout_noAuthHeader_stillSucceedsButDoesNotBlacklist() {
        // Edge case: principal exists but Authorization header is missing
        // (unusual but possible). Logout MUST still proceed for the principal
        // (delete refresh tokens) — denying logout because of a missing
        // header would be hostile UX.
        var principal = sampleUser("alice", "ROLE_USER");

        ResponseEntity<Object> response = controller.logout(null, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jwt, never()).blacklistToken(anyString());
        verify(jwt).deleteRefreshTokensByUsername("alice");
    }

    @Test
    void logout_noAuthHeaderAndNoPrincipal_succeedsAsNoOp() {
        // Both null — request would have been rejected upstream for any
        // protected route, but /logout is reachable per the security config.
        // Must succeed cleanly (200 OK with the standard message).
        ResponseEntity<Object> response = controller.logout(null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jwt, never()).blacklistToken(anyString());
        verify(jwt, never()).deleteRefreshTokensByUsername(anyString());
        verify(auditEventPort, never()).recordEvent(anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void logout_nonBearerAuthHeader_doesNotAttemptBlacklist() {
        // Wrong auth scheme (e.g. "Basic ..." carried over from another
        // session). Must not extract garbage as a token and pass it to
        // blacklistToken (which would pollute the Redis blacklist).
        var principal = sampleUser("alice", "ROLE_USER");

        controller.logout("Basic abc:def", principal);

        verify(jwt, never()).blacklistToken(anyString());
        verify(jwt).deleteRefreshTokensByUsername("alice");
    }
}
