package com.example.customerservice.auth;

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

    public boolean isBlocked(String ip) {
        AttemptRecord record = attempts.get(ip);
        if (record == null) return false;
        if (record.lockedUntil != null && Instant.now().isBefore(record.lockedUntil)) {
            return true;
        }
        if (record.lockedUntil != null && Instant.now().isAfter(record.lockedUntil)) {
            attempts.remove(ip);
            return false;
        }
        return false;
    }

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

    public void recordSuccess(String ip) {
        attempts.remove(ip);
    }

    public int getRemainingAttempts(String ip) {
        AttemptRecord record = attempts.get(ip);
        if (record == null) return MAX_ATTEMPTS;
        return Math.max(0, MAX_ATTEMPTS - record.count);
    }

    private record AttemptRecord(int count, Instant firstAttempt, Instant lockedUntil) {}
}
