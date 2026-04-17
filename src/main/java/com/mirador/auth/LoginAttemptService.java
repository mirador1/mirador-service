package com.mirador.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks failed login attempts per IP address to prevent brute-force attacks.
 *
 * <p>After {@value #MAX_ATTEMPTS} consecutive failures within {@value #LOCKOUT_MINUTES} minutes,
 * the IP is locked out. The lockout resets after the window expires or after a successful login.
 *
 * <p>Uses an in-memory {@link ConcurrentHashMap} — in a multi-instance deployment,
 * replace with Redis (shared across replicas).
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    static final int MAX_ATTEMPTS = 5;
    static final int LOCKOUT_MINUTES = 15;

    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    /**
     * Returns {@code true} if the IP is currently locked out (within its lockout window).
     *
     * <p>As a side effect, expired lockout records are removed from memory on this call to
     * avoid unbounded map growth for clients that stop retrying after being locked out.
     *
     * @param ip the client IP address from the request (X-Forwarded-For or RemoteAddr)
     */
    public boolean isBlocked(String ip) {
        AttemptRecord attempt = attempts.get(ip);
        if (attempt == null) return false;
        if (attempt.lockedUntil != null && Instant.now().isBefore(attempt.lockedUntil)) {
            return true;
        }
        if (attempt.lockedUntil != null && Instant.now().isAfter(attempt.lockedUntil)) {
            // Auto-expire: remove the record when the lockout window has passed
            attempts.remove(ip);
            return false;
        }
        return false;
    }

    /**
     * Records a failed login attempt for an IP address.
     *
     * <p>Automatically triggers a lockout (logged at WARN) when {@value #MAX_ATTEMPTS}
     * consecutive failures are recorded within the window.
     *
     * @apiNote This method is called on every failed authentication attempt, including attempts
     *          on non-existent usernames (to prevent username enumeration via timing differences).
     */
    public void recordFailure(String ip) {
        attempts.compute(ip, (key, existing) -> {
            if (existing == null) {
                return new AttemptRecord(1, Instant.now(), null);
            }
            int newCount = existing.count + 1;
            Instant lockedUntil = null;
            if (newCount >= MAX_ATTEMPTS) {
                lockedUntil = Instant.now().plusSeconds(LOCKOUT_MINUTES * 60L);
                log.warn("brute_force_lockout ip={} attempts={} locked_until={}", ip, newCount, lockedUntil);
            }
            return new AttemptRecord(newCount, existing.firstAttempt, lockedUntil);
        });
    }

    /**
     * Clears all recorded failures for an IP on successful login.
     * Ensures a successful login resets the counter for legitimate users who
     * mistyped their password a few times before getting it right.
     */
    public void recordSuccess(String ip) {
        attempts.remove(ip);
    }

    /**
     * Returns the number of attempts remaining before the IP is locked out.
     * Returns {@value #MAX_ATTEMPTS} for IPs with no recorded failures.
     *
     * <p>This value is included in {@code 401} responses so clients can display
     * "2 attempts remaining" warnings to legitimate users.
     */
    public int getRemainingAttempts(String ip) {
        AttemptRecord attempt = attempts.get(ip);
        if (attempt == null) return MAX_ATTEMPTS;
        return Math.max(0, MAX_ATTEMPTS - attempt.count);
    }

    private record AttemptRecord(int count, Instant firstAttempt, Instant lockedUntil) {}
}
