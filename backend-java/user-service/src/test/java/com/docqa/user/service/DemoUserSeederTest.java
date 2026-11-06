package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.user.config.DemoUserProperties;
import com.docqa.user.domain.NewUser;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import com.docqa.user.repository.UserRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class DemoUserSeederTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String BCRYPT_HASH = new BCryptPasswordEncoder(4).encode("demo-pass-123");

    private final ApplicationArguments arguments = mock(ApplicationArguments.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private DemoUserProperties properties;

    @BeforeEach
    void setUp() {
        properties = new DemoUserProperties();
        properties.setUsername(" Demo ");
        properties.setPasswordHash(BCRYPT_HASH);
        properties.setUserId(USER_ID);
        properties.setAdmin(true);
    }

    @Test
    void seedsAnAdminAccountWithANormalisedUsername() {
        seeder().run(arguments);

        ArgumentCaptor<NewUser> userCaptor = ArgumentCaptor.forClass(NewUser.class);
        verify(userRepository).insert(userCaptor.capture(), eq(EnumSet.of(UserRole.USER, UserRole.ADMIN)));
        NewUser user = userCaptor.getValue();
        assertThat(user.id()).isEqualTo(USER_ID);
        assertThat(user.username()).isEqualTo("demo");
        assertThat(user.passwordHash()).isEqualTo(BCRYPT_HASH);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.createdAt()).isEqualTo(NOW);
    }

    @Test
    void seedsAUserWhenAdminIsDisabled() {
        properties.setAdmin(false);

        seeder().run(arguments);

        verify(userRepository).insert(any(), eq(Set.of(UserRole.USER)));
    }

    @Test
    void skipsWhenTheAccountAlreadyExists() {
        when(userRepository.existsByUsername("demo")).thenReturn(true);

        seeder().run(arguments);

        verify(userRepository, never()).insert(any(), any());
    }

    @Test
    void skipsWhenSeedingIsDisabled() {
        properties.setEnabled(false);
        properties.setPasswordHash("not-a-bcrypt-hash");

        seeder().run(arguments);

        verify(userRepository, never()).existsByUsername(any());
        verify(userRepository, never()).insert(any(), any());
    }

    @Test
    void skipsWhenNoPasswordHashIsConfigured() {
        properties.setPasswordHash(" ");

        seeder().run(arguments);

        verify(userRepository, never()).insert(any(), any());
    }

    @Test
    void refusesANonBcryptHash() {
        properties.setPasswordHash("plain-text-password");

        assertThatThrownBy(() -> seeder().run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BCrypt");
        verify(userRepository, never()).insert(any(), any());
    }

    @Test
    void refusesABlankUsername() {
        properties.setUsername("   ");

        assertThatThrownBy(() -> seeder().run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username");
    }

    private DemoUserSeeder seeder() {
        return new DemoUserSeeder(properties, userRepository, new MutableClock(NOW));
    }
}
