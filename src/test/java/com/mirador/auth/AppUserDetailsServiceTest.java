package com.mirador.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppUserDetailsService} — the bridge between the
 * project's {@link AppUser} JPA entity and Spring Security's
 * {@link UserDetails} contract.
 *
 * <p>Verifies the field-by-field translation: username, password (BCrypt
 * hash passed through unchanged), enabled flag, and role mapped to a
 * single {@code SimpleGrantedAuthority}.
 */
class AppUserDetailsServiceTest {

    private AppUserRepository repository;
    private AppUserDetailsService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppUserRepository.class);
        service = new AppUserDetailsService(repository);
    }

    private AppUser sampleUser(String username, String role, boolean enabled) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPassword("$2a$10$hashedpassword");
        u.setRole(role);
        u.setEnabled(enabled);
        return u;
    }

    @Test
    void loadUserByUsername_existing_returnsUserDetailsWithCorrectFields() {
        when(repository.findByUsername("alice")).thenReturn(
                Optional.of(sampleUser("alice", "ROLE_ADMIN", true)));

        UserDetails details = service.loadUserByUsername("alice");

        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).isEqualTo("$2a$10$hashedpassword");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
    }

    @Test
    void loadUserByUsername_existing_mapsRoleToSingleGrantedAuthority() {
        when(repository.findByUsername("admin")).thenReturn(
                Optional.of(sampleUser("admin", "ROLE_ADMIN", true)));

        UserDetails details = service.loadUserByUsername("admin");

        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void loadUserByUsername_disabledAccount_propagatesEnabledFalse() {
        // Spring Security's authentication chain checks isEnabled() before
        // password — a disabled user must surface as such even with valid
        // credentials. Test pins the wiring.
        when(repository.findByUsername("retired")).thenReturn(
                Optional.of(sampleUser("retired", "ROLE_USER", false)));

        UserDetails details = service.loadUserByUsername("retired");

        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void loadUserByUsername_unknown_throwsUsernameNotFoundException() {
        // Contract: Spring Security expects this specific exception (not a
        // generic NoSuchElement) so the auth chain can produce a 401, not 500.
        when(repository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void loadUserByUsername_passwordIsPassedThroughUnchanged() {
        // Password must NOT be re-hashed or modified — the BCrypt hash from
        // the DB is what BCryptPasswordEncoder.matches() compares against.
        // Test guards against accidental .toLowerCase() / trim() / re-hash.
        AppUser u = sampleUser("bob", "ROLE_USER", true);
        u.setPassword("  $2a$10$VeryLongHashWithSpaces  ");
        when(repository.findByUsername("bob")).thenReturn(Optional.of(u));

        UserDetails details = service.loadUserByUsername("bob");

        assertThat(details.getPassword()).isEqualTo("  $2a$10$VeryLongHashWithSpaces  ");
    }
}
