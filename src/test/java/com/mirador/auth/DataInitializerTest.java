package com.mirador.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DataInitializer} — the seeder that creates the
 * three demo users on startup.
 *
 * <p>Critical contract: the seeder must be IDEMPOTENT (re-run safe). On
 * the first start it inserts; on every subsequent start it must skip
 * each user that already exists. Without this, every restart would
 * either fail (unique constraint violation on username) or create
 * duplicates depending on the schema.
 */
class DataInitializerTest {

    private AppUserRepository repository;
    private PasswordEncoder passwordEncoder;
    private DataInitializer initializer;

    @BeforeEach
    void setUp() {
        repository = mock(AppUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        // BCrypt encoder is mocked to a deterministic suffix so we can
        // assert the password value was actually re-hashed (not stored raw).
        when(passwordEncoder.encode(any(String.class)))
                .thenAnswer(inv -> "bcrypt:" + inv.getArgument(0));
        initializer = new DataInitializer(repository, passwordEncoder);
    }

    @Test
    void run_emptyDatabase_seedsAllThreeDemoUsersWithCorrectRoles() {
        // Default: empty repository — every findByUsername returns empty,
        // so all three users get inserted.
        when(repository.findByUsername(any(String.class))).thenReturn(Optional.empty());

        initializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository, times(3)).save(captor.capture());

        var saved = captor.getAllValues();
        assertThat(saved).extracting(AppUser::getUsername)
                .containsExactly("admin", "user", "viewer");
        assertThat(saved).extracting(AppUser::getRole)
                .containsExactly("ROLE_ADMIN", "ROLE_USER", "ROLE_READER");
        assertThat(saved).allMatch(AppUser::isEnabled);
    }

    @Test
    void run_storesBcryptHashedPasswords_neverPlaintext() {
        // Critical security guard: passwords must be hashed via the injected
        // PasswordEncoder before save. Test pins the hash-before-save call
        // chain so a future "fix" that bypasses encoder.encode() fails fast.
        when(repository.findByUsername(any(String.class))).thenReturn(Optional.empty());

        initializer.run(new DefaultApplicationArguments());

        verify(passwordEncoder).encode("admin");
        verify(passwordEncoder).encode("user");
        verify(passwordEncoder).encode("viewer");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository, times(3)).save(captor.capture());
        // Stub returned "bcrypt:<input>" — proves the encoded value, not the
        // raw password, hit the entity setter.
        assertThat(captor.getAllValues()).extracting(AppUser::getPassword)
                .containsExactly("bcrypt:admin", "bcrypt:user", "bcrypt:viewer");
    }

    @Test
    void run_existingUsersAreSkipped_idempotentReRun() {
        // Idempotency contract: if all 3 users already exist, run() does
        // nothing. Without this, the second startup would either crash on
        // the unique-username constraint or create duplicates.
        when(repository.findByUsername(any(String.class)))
                .thenReturn(Optional.of(new AppUser()));

        initializer.run(new DefaultApplicationArguments());

        verify(repository, never()).save(any(AppUser.class));
        verify(passwordEncoder, never()).encode(any(String.class));
    }

    @Test
    void run_partialState_seedsOnlyMissingUsers() {
        // Realistic recovery scenario: admin exists (from a previous run that
        // crashed mid-seed), user + viewer don't. The seeder must add the
        // missing two without re-touching admin's record (which could rotate
        // its password unintentionally).
        when(repository.findByUsername("admin")).thenReturn(Optional.of(new AppUser()));
        when(repository.findByUsername("user")).thenReturn(Optional.empty());
        when(repository.findByUsername("viewer")).thenReturn(Optional.empty());

        initializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AppUser::getUsername)
                .containsExactly("user", "viewer");

        // Critical: admin's encoder.encode was NOT called → we did not
        // touch the existing admin entity (and didn't rotate the password).
        verify(passwordEncoder, never()).encode("admin");
    }
}
