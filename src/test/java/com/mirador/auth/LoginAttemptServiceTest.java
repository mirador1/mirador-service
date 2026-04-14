package com.mirador.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for in-memory brute-force protection.
 * Pure logic — no Spring context, no mocks.
 */
class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    void unknownIp_isNotBlocked() {
        assertThat(service.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void unknownIp_hasMaxRemainingAttempts() {
        assertThat(service.getRemainingAttempts("1.2.3.4"))
                .isEqualTo(LoginAttemptService.MAX_ATTEMPTS);
    }

    @Test
    void recordFailures_decreasesRemainingAttempts() {
        service.recordFailure("5.5.5.5");
        service.recordFailure("5.5.5.5");

        assertThat(service.getRemainingAttempts("5.5.5.5"))
                .isEqualTo(LoginAttemptService.MAX_ATTEMPTS - 2);
    }

    @Test
    void afterMaxAttempts_ipIsBlocked() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("attacker");
        }
        assertThat(service.isBlocked("attacker")).isTrue();
        assertThat(service.getRemainingAttempts("attacker")).isZero();
    }

    @Test
    void recordSuccess_clearsAttempts_andUnblocks() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("victim");
        }
        assertThat(service.isBlocked("victim")).isTrue();

        service.recordSuccess("victim");

        assertThat(service.isBlocked("victim")).isFalse();
        assertThat(service.getRemainingAttempts("victim"))
                .isEqualTo(LoginAttemptService.MAX_ATTEMPTS);
    }

    @Test
    void ipIsolation_oneIpDoesNotAffectAnother() {
        service.recordFailure("a.b.c.d");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void remainingAttempts_neverNegative() {
        // record more failures than the max
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS + 3; i++) {
            service.recordFailure("flood");
        }
        assertThat(service.getRemainingAttempts("flood")).isZero();
    }
}
