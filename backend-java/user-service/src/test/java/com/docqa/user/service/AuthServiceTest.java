package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.user.config.AuthProperties;
import com.docqa.user.config.JwtProperties;
import com.docqa.user.domain.RefreshTokenStatus;
import com.docqa.user.domain.TenantRecord;
import com.docqa.user.domain.UserCredentials;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import com.docqa.user.dto.ChangePasswordRequest;
import com.docqa.user.dto.CurrentUserData;
import com.docqa.user.dto.LoginRequest;
import com.docqa.user.dto.RegisterRequest;
import com.docqa.user.error.RateLimitedException;
import com.docqa.user.repository.RoleRepository;
import com.docqa.user.repository.TenantRepository;
import com.docqa.user.repository.UserRepository;
import com.docqa.user.support.InMemoryIdempotencyStore;
import com.docqa.user.support.InMemoryRefreshTokenRepository;
import com.docqa.user.support.RecordingRevocationStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String USERNAME = "demo";
    private static final String PASSWORD = "test-password-123";
    private static final String NEW_PASSWORD = "new-battery-11";
    private static final String IP = "127.0.0.1";
    private static final String IDEMPOTENCY_KEY = "idem-key-0001";
    private static final UUID SECOND_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String SECOND_SLUG = "acme";
    private static final String SECOND_PASSWORD = "acme-password-456";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MutableClock clock;
    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private TenantRepository tenantRepository;
    private InMemoryRefreshTokenRepository refreshTokenRepository;
    private RecordingRevocationStore revocationStore;
    private RegistrationRateLimiter registrationRateLimiter;
    private LoginRateLimiter loginRateLimiter;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthProperties properties;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        tenantRepository = mock(TenantRepository.class);
        refreshTokenRepository = new InMemoryRefreshTokenRepository();
        revocationStore = new RecordingRevocationStore();
        registrationRateLimiter = mock(RegistrationRateLimiter.class);
        when(registrationRateLimiter.check(any(), any())).thenReturn(RateLimitDecision.permit());
        loginRateLimiter = mock(LoginRateLimiter.class);
        when(loginRateLimiter.check(any(), any())).thenReturn(RateLimitDecision.permit());
        when(roleRepository.findRoles(any(), any())).thenReturn(Set.of(UserRole.USER));

        properties = new AuthProperties();
        properties.setMaxLoginFailures(5);
        properties.setLoginFailureWindow(Duration.ofSeconds(60));
        properties.setRefreshTtl(Duration.ofDays(14));
        passwordEncoder = new BCryptPasswordEncoder(4);

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("test-issuer");
        jwtProperties.setAudience("test-audience");
        jwtProperties.setTtl(Duration.ofMinutes(15));
        jwtProperties.setSecret("test-jwt-secret-with-at-least-32-bytes-1234567890");
        jwtService = new JwtService(jwtProperties, clock);

        authService = new AuthService(
                userRepository,
                roleRepository,
                new RefreshTokenService(refreshTokenRepository, properties, clock),
                new AccessTokenAuthenticator(jwtService, revocationStore),
                new PasswordPolicy(),
                new IdempotencyGuard(new InMemoryIdempotencyStore(), objectMapper, clock),
                registrationRateLimiter,
                loginRateLimiter,
                jwtService,
                passwordEncoder,
                properties,
                new TenantResolver(tenantRepository, properties),
                clock);
    }

    @Test
    void loginIssuesAccessTokenWithRolesAndStartsRefreshFamily() {
        givenUser(UserStatus.ACTIVE);

        IssuedSession session = authService.login(new LoginRequest(USERNAME, PASSWORD), IP);

        JwtIdentity identity = jwtService.verify(session.access().accessToken());
        assertThat(identity.userId()).isEqualTo(USER_ID);
        assertThat(identity.roles()).containsExactly(UserRole.USER);
        assertThat(session.access().tokenType()).isEqualTo("Bearer");
        assertThat(session.access().expiresIn()).isEqualTo(900);
        assertThat(session.refreshToken()).isNotBlank();
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.ACTIVE)).isEqualTo(1);
        verify(userRepository).touchLastLogin(
                eq(TenantContext.DEFAULT_TENANT_ID), eq(USER_ID), eq(NOW));
    }

    @Test
    void loginNormalisesUsernameBeforeLookup() {
        when(userRepository.findCredentialsByUsername(TenantContext.DEFAULT_TENANT_ID, "zhang.wei"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest(" Zhang.Wei ", PASSWORD), IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_AUTH_001));
    }

    @Test
    void loginRejectsUnknownUsernameAndWrongPasswordIdentically() {
        givenUser(UserStatus.ACTIVE);

        assertThatThrownBy(() -> authService.login(new LoginRequest(USERNAME, "wrong-password"), IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_AUTH_001));
        assertThatThrownBy(() -> authService.login(new LoginRequest("unknown", PASSWORD), IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_AUTH_001));
        verify(userRepository, never()).touchLastLogin(any(), any(), any());
    }

    @Test
    void loginBlocksSixthAttemptInTheSameWindow() {
        givenUser(UserStatus.ACTIVE);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(USERNAME, "wrong-password"), IP))
                    .isInstanceOf(BusinessException.class);
        }

        // 限流计数语义由 LoginRateLimiterTest 覆盖；这里只验证 AuthService 对 reject 的映射。
        when(loginRateLimiter.check(any(), any())).thenReturn(RateLimitDecision.reject(60));
        assertThatThrownBy(() -> authService.login(new LoginRequest(USERNAME, PASSWORD), IP))
                .isInstanceOfSatisfying(RateLimitedException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_RATE_LIMIT_001);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(60);
                });
    }

    @Test
    void loginRejectsDisabledAccountWithDistinctCode() {
        givenUser(UserStatus.DISABLED);

        assertThatThrownBy(() -> authService.login(new LoginRequest(USERNAME, PASSWORD), IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_DISABLED_001));
    }

    @Test
    void loginRejectsPasswordLongerThanBcryptLimit() {
        assertThatThrownBy(() -> authService.login(new LoginRequest(USERNAME, "a".repeat(73)), IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001));
    }

    @Test
    void registerCreatesLowercaseAccountWithDefaultRole() {
        when(userRepository.existsByUsername(TenantContext.DEFAULT_TENANT_ID, "zhang.wei"))
                .thenReturn(false);

        IssuedSession session = authService.register(
                new RegisterRequest("Zhang.Wei", "correct-horse-9"), IDEMPOTENCY_KEY, IP);

        ArgumentCaptor<com.docqa.user.domain.NewUser> userCaptor =
                ArgumentCaptor.forClass(com.docqa.user.domain.NewUser.class);
        verify(userRepository).insert(
                eq(TenantContext.DEFAULT_TENANT_ID), userCaptor.capture(), eq(Set.of(UserRole.USER)));
        assertThat(userCaptor.getValue().username()).isEqualTo("zhang.wei");
        assertThat(userCaptor.getValue().status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userCaptor.getValue().passwordHash()).startsWith("$2");
        assertThat(session.refreshToken()).isNotBlank();
        assertThat(jwtService.verify(session.access().accessToken()).userId())
                .isEqualTo(userCaptor.getValue().id());
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userRepository.existsByUsername(TenantContext.DEFAULT_TENANT_ID, "zhang.wei"))
                .thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("zhang.wei", "correct-horse-9"), IDEMPOTENCY_KEY, IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_CONFLICT_001));
        verify(userRepository, never()).insert(any(), any(), any());
    }

    @Test
    void registerRejectsWeakPasswordWithoutEchoingIt() {
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("zhang.wei", "short1"), IDEMPOTENCY_KEY, IP))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_VALIDATION_001);
                    assertThat(exception.getFieldViolations())
                            .allMatch(violation -> violation.field().equals("password"));
                    assertThat(exception.getFieldViolations())
                            .noneMatch(violation -> violation.message().contains("short1"));
                });
        verify(userRepository, never()).insert(any(), any(), any());
    }

    @Test
    void registerReplayReturnsFirstBodyWithoutIssuingSecondFamily() {
        when(userRepository.existsByUsername(TenantContext.DEFAULT_TENANT_ID, "zhang.wei"))
                .thenReturn(false);
        RegisterRequest request = new RegisterRequest("zhang.wei", "correct-horse-9");

        IssuedSession first = authService.register(request, IDEMPOTENCY_KEY, IP);
        IssuedSession replay = authService.register(request, IDEMPOTENCY_KEY, IP);

        assertThat(replay.access().accessToken()).isEqualTo(first.access().accessToken());
        assertThat(replay.refreshToken()).isNull();
        verify(userRepository, times(1)).insert(any(), any(), any());
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void registerRejectsDifferentBodyForTheSameKey() {
        when(userRepository.existsByUsername(any(), anyString())).thenReturn(false);

        authService.register(new RegisterRequest("zhang.wei", "correct-horse-9"), IDEMPOTENCY_KEY, IP);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("zhang.wei", "another-pass-2"), IDEMPOTENCY_KEY, IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_IDEMPOTENCY_001));
    }

    @Test
    void registerRejectsWhenSelfRegistrationIsDisabled() {
        properties.setSelfRegistrationEnabled(false);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("zhang.wei", "correct-horse-9"), IDEMPOTENCY_KEY, IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_FORBIDDEN_001));
    }

    @Test
    void registerAppliesSourceRateLimitBeforeAnythingElse() {
        when(registrationRateLimiter.check(eq(IP), any())).thenReturn(RateLimitDecision.reject(42));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("zhang.wei", "correct-horse-9"), IDEMPOTENCY_KEY, IP))
                .isInstanceOfSatisfying(RateLimitedException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COM_RATE_LIMIT_001);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(42);
                });
        verify(userRepository, never()).existsByUsername(any(), anyString());
    }

    @Test
    void refreshRotatesAndTreatsReuseAsCompromise() {
        givenUser(UserStatus.ACTIVE);
        IssuedSession session = authService.login(new LoginRequest(USERNAME, PASSWORD), IP);

        IssuedSession rotated = authService.refresh(session.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(session.refreshToken());
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.USED)).isEqualTo(1);
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.ACTIVE)).isEqualTo(1);

        assertThatThrownBy(() -> authService.refresh(session.refreshToken()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_REFRESH_001));
        assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_REFRESH_001));
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.ACTIVE)).isZero();
    }

    @Test
    void refreshRejectsUnknownOrMissingToken() {
        assertThatThrownBy(() -> authService.refresh(null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_REFRESH_001));
        assertThatThrownBy(() -> authService.refresh("not-a-known-token"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_REFRESH_001));
    }

    @Test
    void refreshRejectsDisabledAccountAndRevokesTheNewFamily() {
        givenUser(UserStatus.ACTIVE);
        IssuedSession session = authService.login(new LoginRequest(USERNAME, PASSWORD), IP);
        when(userRepository.findCredentialsById(TenantContext.DEFAULT_TENANT_ID, USER_ID)).thenReturn(Optional.of(
                new UserCredentials(USER_ID, USERNAME, passwordEncoder.encode(PASSWORD), UserStatus.DISABLED)));

        assertThatThrownBy(() -> authService.refresh(session.refreshToken()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_REFRESH_001));
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.ACTIVE)).isZero();
    }

    @Test
    void logoutRevokesAccessTokenAndRefreshFamily() {
        givenUser(UserStatus.ACTIVE);
        IssuedSession session = authService.login(new LoginRequest(USERNAME, PASSWORD), IP);
        JwtIdentity identity = jwtService.verify(session.access().accessToken());

        authService.logout("Bearer " + session.access().accessToken(), session.refreshToken());

        assertThat(revocationStore.revokedTokenIds()).containsExactly(identity.tokenId());
        assertThat(revocationStore.ttlOf(identity.tokenId())).isEqualTo(Duration.ofMinutes(15));
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.REVOKED)).isEqualTo(1);
    }

    @Test
    void logoutWithoutCredentialsStaysSilent() {
        authService.logout(null, null);

        assertThat(revocationStore.revokedTokenIds()).isEmpty();
        assertThat(refreshTokenRepository.all()).isEmpty();
    }

    @Test
    void changePasswordRevokesEverySessionAndAdvancesTheWatermark() {
        givenUser(UserStatus.ACTIVE);
        IssuedSession session = authService.login(new LoginRequest(USERNAME, PASSWORD), IP);
        JwtIdentity identity = jwtService.verify(session.access().accessToken());

        authService.changePassword(
                identity,
                new ChangePasswordRequest(PASSWORD, NEW_PASSWORD),
                IDEMPOTENCY_KEY,
                IP);

        verify(userRepository).updatePasswordHash(
                eq(TenantContext.DEFAULT_TENANT_ID), eq(USER_ID), anyString(), eq(NOW));
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.REVOKED)).isEqualTo(1);
        assertThat(revocationStore.watermarkOf(USER_ID)).contains(NOW);
        assertThat(revocationStore.userTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        givenUser(UserStatus.ACTIVE);
        JwtIdentity identity = jwtService.verify(issueAccessToken());

        assertThatThrownBy(() -> authService.changePassword(
                identity,
                new ChangePasswordRequest("wrong-password", NEW_PASSWORD),
                IDEMPOTENCY_KEY,
                IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_AUTH_001));
        verify(userRepository, never()).updatePasswordHash(any(), any(), anyString(), any());
    }

    @Test
    void changePasswordRejectsReusingTheCurrentPassword() {
        givenUser(UserStatus.ACTIVE);
        JwtIdentity identity = jwtService.verify(issueAccessToken());

        assertThatThrownBy(() -> authService.changePassword(
                identity,
                new ChangePasswordRequest(PASSWORD, PASSWORD),
                IDEMPOTENCY_KEY,
                IP))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_VALIDATION_001);
                    assertThat(exception.getFieldViolations()).singleElement()
                            .satisfies(violation -> assertThat(violation.field()).isEqualTo("newPassword"));
                });
    }

    @Test
    void changePasswordRejectsWeakNewPassword() {
        givenUser(UserStatus.ACTIVE);
        JwtIdentity identity = jwtService.verify(issueAccessToken());

        assertThatThrownBy(() -> authService.changePassword(
                identity,
                new ChangePasswordRequest(PASSWORD, "onlyletters"),
                IDEMPOTENCY_KEY,
                IP))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_VALIDATION_001);
                    assertThat(exception.getFieldViolations())
                            .allMatch(violation -> violation.field().equals("newPassword"));
                });
    }

    @Test
    void changePasswordReplayDoesNotRecheckTheOldCredential() {
        givenUser(UserStatus.ACTIVE);
        JwtIdentity identity = jwtService.verify(issueAccessToken());
        ChangePasswordRequest request = new ChangePasswordRequest(PASSWORD, NEW_PASSWORD);

        authService.changePassword(identity, request, IDEMPOTENCY_KEY, IP);
        authService.changePassword(identity, request, IDEMPOTENCY_KEY, IP);

        verify(userRepository, times(1)).updatePasswordHash(any(), any(), anyString(), any());
        verify(userRepository, times(1)).findCredentialsById(
                TenantContext.DEFAULT_TENANT_ID, USER_ID);
    }

    @Test
    void currentUserReadsRolesFromTokenAndStatusFromDatabase() {
        givenUser(UserStatus.ACTIVE);
        JwtIdentity identity = jwtService.verify(
                jwtService.issue(USER_ID, TenantContext.DEFAULT_TENANT_ID, Set.of(UserRole.USER, UserRole.ADMIN)));

        CurrentUserData currentUser = authService.currentUser(identity);

        assertThat(currentUser.userId()).isEqualTo(USER_ID);
        assertThat(currentUser.username()).isEqualTo(USERNAME);
        assertThat(currentUser.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(currentUser.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
        assertThat(currentUser.accessTokenExpiresIn()).isEqualTo(900);
    }

    @Test
    void currentUserRejectsDisabledAccountEvenWithAValidToken() {
        givenUser(UserStatus.DISABLED);
        JwtIdentity identity = jwtService.verify(issueAccessToken());

        assertThatThrownBy(() -> authService.currentUser(identity))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_DISABLED_001));
    }

    @Test
    void loginWithExplicitSlugUsesTheResolvedTenantForCredentialsAndJwt() {
        givenTenant(SECOND_SLUG, SECOND_TENANT, "ACTIVE");
        UserCredentials credentials = new UserCredentials(
                USER_ID, USERNAME, passwordEncoder.encode(SECOND_PASSWORD), UserStatus.ACTIVE);
        when(userRepository.findCredentialsByUsername(SECOND_TENANT, USERNAME))
                .thenReturn(Optional.of(credentials));

        IssuedSession session =
                authService.login(new LoginRequest(USERNAME, SECOND_PASSWORD, " ACME "), IP);

        JwtIdentity identity = jwtService.verify(session.access().accessToken());
        assertThat(identity.tenantId()).isEqualTo(SECOND_TENANT);
        verify(userRepository).touchLastLogin(eq(SECOND_TENANT), eq(USER_ID), eq(NOW));
        verify(userRepository, never())
                .findCredentialsByUsername(eq(TenantContext.DEFAULT_TENANT_ID), anyString());
    }

    @Test
    void loginRejectsUnknownOrNonLoginTenantWithTheGenericCredentialError() {
        when(tenantRepository.findBySlug("ghost")).thenReturn(Optional.empty());
        when(tenantRepository.findBySlug("suspended"))
                .thenReturn(Optional.of(new TenantRecord(SECOND_TENANT, "suspended", "SUSPENDED")));

        assertThatThrownBy(() -> authService.login(new LoginRequest(USERNAME, PASSWORD, "ghost"), IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_AUTH_001));
        assertThatThrownBy(() -> authService.login(new LoginRequest(USERNAME, PASSWORD, "suspended"), IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_AUTH_001));
        verify(userRepository, never()).findCredentialsByUsername(any(), anyString());
    }

    @Test
    void registerRejectsActiveTenantNotInTheWhitelist() {
        // acme 是 ACTIVE 租户且可登录，但未列入注册白名单：不得凭猜中 slug 自助入驻。
        givenTenant(SECOND_SLUG, SECOND_TENANT, "ACTIVE");

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("new.user", "correct-horse-9", SECOND_SLUG),
                IDEMPOTENCY_KEY, IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USR_AUTH_001));
        verify(userRepository, never()).insert(any(), any(), any());
    }

    @Test
    void sameUsernameInTwoTenantsDoesNotShareTheLoginFailureBudget() {
        givenUser(UserStatus.ACTIVE);
        givenTenant(SECOND_SLUG, SECOND_TENANT, "TRIAL");
        when(userRepository.findCredentialsByUsername(SECOND_TENANT, USERNAME))
                .thenReturn(Optional.of(new UserCredentials(
                        USER_ID, USERNAME, passwordEncoder.encode(SECOND_PASSWORD), UserStatus.ACTIVE)));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> authService.login(
                    new LoginRequest(USERNAME, "wrong-password", SECOND_SLUG), IP))
                    .isInstanceOf(BusinessException.class);
        }

        IssuedSession session = authService.login(new LoginRequest(USERNAME, PASSWORD), IP);
        assertThat(jwtService.verify(session.access().accessToken()).tenantId())
                .isEqualTo(TenantContext.DEFAULT_TENANT_ID);
    }

    @Test
    void refreshStaysInsideTheTenantThatOwnsTheCookie() {
        givenTenant(SECOND_SLUG, SECOND_TENANT, "ACTIVE");
        UserCredentials credentials = new UserCredentials(
                USER_ID, USERNAME, passwordEncoder.encode(SECOND_PASSWORD), UserStatus.ACTIVE);
        when(userRepository.findCredentialsByUsername(SECOND_TENANT, USERNAME))
                .thenReturn(Optional.of(credentials));
        when(userRepository.findCredentialsById(SECOND_TENANT, USER_ID))
                .thenReturn(Optional.of(credentials));

        IssuedSession session =
                authService.login(new LoginRequest(USERNAME, SECOND_PASSWORD, SECOND_SLUG), IP);
        IssuedSession rotated = authService.refresh(session.refreshToken());

        assertThat(jwtService.verify(rotated.access().accessToken()).tenantId())
                .isEqualTo(SECOND_TENANT);
        verify(userRepository, never()).findCredentialsById(TenantContext.DEFAULT_TENANT_ID, USER_ID);
    }

    private void givenTenant(String slug, UUID tenantId, String status) {
        when(tenantRepository.findBySlug(slug))
                .thenReturn(Optional.of(new TenantRecord(tenantId, slug, status)));
    }

    private void givenUser(UserStatus status) {
        String hash = passwordEncoder.encode(PASSWORD);
        UserCredentials credentials = new UserCredentials(USER_ID, USERNAME, hash, status);
        when(userRepository.findCredentialsByUsername(TenantContext.DEFAULT_TENANT_ID, USERNAME))
                .thenReturn(Optional.of(credentials));
        when(userRepository.findCredentialsById(TenantContext.DEFAULT_TENANT_ID, USER_ID))
                .thenReturn(Optional.of(credentials));
        when(userRepository.findById(TenantContext.DEFAULT_TENANT_ID, USER_ID)).thenReturn(Optional.of(
                new com.docqa.user.domain.AppUserRow(USER_ID, USERNAME, status, null, NOW)));
    }

    private String issueAccessToken() {
        return jwtService.issue(USER_ID, TenantContext.DEFAULT_TENANT_ID, Set.of(UserRole.USER));
    }
}
