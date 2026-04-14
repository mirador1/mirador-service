package com.mirador.auth;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * JPA entity for application users, stored in the {@code app_user} table.
 *
 * <p>Passwords are stored as BCrypt hashes — never in plaintext.
 * The {@code role} field stores one of: {@code ROLE_ADMIN}, {@code ROLE_USER}, {@code ROLE_READER}.
 */
@Entity
@Table(name = "app_user")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt hash of the user's password (strength 10). Never store plaintext. */
    @Column(nullable = false, length = 255)
    private String password;

    /** Spring Security role: ROLE_ADMIN | ROLE_USER | ROLE_READER */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT NOW()")
    private Instant createdAt;
}
