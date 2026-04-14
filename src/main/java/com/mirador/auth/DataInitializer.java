package com.mirador.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the three demo users into the {@code app_user} table on startup (if missing).
 *
 * <p>Passwords are BCrypt-hashed at startup using the injected {@link PasswordEncoder},
 * so the hash values are never hardcoded in source or SQL files.
 *
 * <p>Demo accounts:
 * <ul>
 *   <li>{@code admin / admin} — ROLE_ADMIN (full access)</li>
 *   <li>{@code user / user}   — ROLE_USER (read + write)</li>
 *   <li>{@code viewer / viewer} — ROLE_READER (read-only)</li>
 * </ul>
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedUser("admin",  "admin",  "ROLE_ADMIN");
        seedUser("user",   "user",   "ROLE_USER");
        seedUser("viewer", "viewer", "ROLE_READER");
    }

    private void seedUser(String username, String rawPassword, String role) {
        if (userRepository.findByUsername(username).isEmpty()) {
            AppUser user = new AppUser();
            user.setUsername(username);
            // BCrypt strength 10 — OWASP-recommended balance between security and performance (~100ms per hash)
            user.setPassword(passwordEncoder.encode(rawPassword));
            user.setRole(role);
            user.setEnabled(true);
            userRepository.save(user);
            log.info("seeded demo user '{}' with role {}", username, role);
        }
    }
}
