package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.config.JwtProperties;
import com.docqa.user.domain.UserRole;
import com.docqa.user.support.RecordingRevocationStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccessTokenAuthenticatorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private MutableClock clock;
    private JwtService jwtService;
    private RecordingRevocationStore revocationStore;
    private AccessTokenAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("test-issuer");
        jwtProperties.setAudience("test-audience");
        jwtProperties.setTtl(Duration.ofMinutes(15));
        jwtProperties.setSecret("test-jwt-secret-with-at-least-32-bytes-1234567890");
        jwtService = new JwtService(jwtProperties, clock);
        revocationStore = new RecordingRevocationStore();
        authenticator = new AccessTokenAuthenticator(jwtService, revocationStore);
    }

    @Test
    void acceptsAValidBearerToken() {
        JwtIdentity identity = authenticator.authenticate(
                "Bearer " + jwtService.issue(USER_ID, Set.of(UserRole.USER, UserRole.ADMIN)));

        assertThat(identity.userId()).isEqualTo(USER_ID);
        assertThat(identity.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
        assertThat(identity.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(authenticator.tryAuthenticate("bearer " + jwtService.issue(USER_ID, Set.of(UserRole.USER))))
                .isPresent();
    }

    @Test
    void rejectsMalformedAuthorizationHeaders() {
        assertThat(authenticator.tryAuthenticate(null)).isEmpty();
        assertThat(authenticator.tryAuthenticate("")).isEmpty();
        assertThat(authenticator.tryAuthenticate("Token abc")).isEmpty();
        assertThat(authenticator.tryAuthenticate("Bearer ")).isEmpty();
        assertThat(authenticator.tryAuthenticate("Bearer two words")).isEmpty();

        assertThatThrownBy(() -> authenticator.authenticate(null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_AUTH_001));
    }

    @Test
    void rejectsExpiredToken() {
        String header = "Bearer " + jwtService.issue(USER_ID, Set.of(UserRole.USER));
        clock.advance(Duration.ofMinutes(16));

        assertThat(authenticator.tryAuthenticate(header)).isEmpty();
    }

    @Test
    void rejectsRevokedTokenId() {
        String header = "Bearer " + jwtService.issue(USER_ID, Set.of(UserRole.USER));
        JwtIdentity identity = authenticator.authenticate(header);

        authenticator.revoke(identity, clock.instant());

        assertThat(revocationStore.revokedTokenIds()).containsExactly(identity.tokenId());
        assertThat(authenticator.tryAuthenticate(header)).isEmpty();
    }

    @Test
    void revokeUsesTheRemainingLifetime() {
        String header = "Bearer " + jwtService.issue(USER_ID, Set.of(UserRole.USER));
        JwtIdentity identity = authenticator.authenticate(header);
        clock.advance(Duration.ofMinutes(5));

        authenticator.revoke(identity, clock.instant());

        assertThat(revocationStore.ttlOf(identity.tokenId())).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsTokenIssuedBeforeTheUserWatermark() {
        String header = "Bearer " + jwtService.issue(USER_ID, Set.of(UserRole.USER));

        authenticator.revokeAllIssuedBefore(USER_ID, NOW.plusSeconds(1));

        assertThat(revocationStore.watermarkOf(USER_ID)).contains(NOW.plusSeconds(1));
        assertThat(revocationStore.userTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(authenticator.tryAuthenticate(header)).isEmpty();
    }

    @Test
    void acceptsTokenIssuedExactlyAtTheWatermark() {
        String header = "Bearer " + jwtService.issue(USER_ID, Set.of(UserRole.USER));

        revocationStore.revokeUser(USER_ID, NOW, Duration.ofMinutes(15));

        Optional<JwtIdentity> identity = authenticator.tryAuthenticate(header);
        assertThat(identity).isPresent();
    }

    @Test
    void acceptsTokenIssuedAfterTheWatermark() {
        revocationStore.revokeUser(USER_ID, NOW, Duration.ofMinutes(15));
        clock.advance(Duration.ofSeconds(1));

        String header = "Bearer " + jwtService.issue(USER_ID, Set.of(UserRole.USER));

        assertThat(authenticator.tryAuthenticate(header)).isPresent();
    }
}
