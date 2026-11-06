package com.docqa.user.service;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.config.AuthProperties;
import com.docqa.user.domain.AppUserRow;
import com.docqa.user.domain.IdempotencyOperation;
import com.docqa.user.domain.NewUser;
import com.docqa.user.domain.UserCredentials;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import com.docqa.user.dto.ChangePasswordRequest;
import com.docqa.user.dto.CurrentUserData;
import com.docqa.user.dto.LoginRequest;
import com.docqa.user.dto.LoginResponse;
import com.docqa.user.dto.RegisterRequest;
import com.docqa.user.error.RateLimitedException;
import com.docqa.user.repository.RoleRepository;
import com.docqa.user.repository.UserRepository;
import com.docqa.user.support.Fingerprints;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 身份用例。凭据判定只发生在这里：userId 只来自已验签令牌的 `sub`，
 * 角色只来自令牌的 `roles` 声明，请求体与 Header 传入的身份一律不参与。
 */
@Service
public class AuthService {

    private static final int PASSWORD_MAX_BYTES = 72;
    private static final int IDEMPOTENCY_KEY_MIN_LENGTH = 8;
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;
    private static final String TOKEN_TYPE = "Bearer";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenAuthenticator accessTokenAuthenticator;
    private final PasswordPolicy passwordPolicy;
    private final IdempotencyGuard idempotencyGuard;
    private final RegistrationRateLimiter registrationRateLimiter;
    private final LoginRateLimiter loginRateLimiter;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final Clock clock;

    /**
     * 用户名不存在时也要跑一次同样代价的摘要比对，否则响应时间会泄露账号是否存在。
     * 摘要每次启动随机生成，仓库里不出现任何可用口令的散列。
     */
    private final String unknownUserPasswordHash;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenService refreshTokenService,
            AccessTokenAuthenticator accessTokenAuthenticator,
            PasswordPolicy passwordPolicy,
            IdempotencyGuard idempotencyGuard,
            RegistrationRateLimiter registrationRateLimiter,
            LoginRateLimiter loginRateLimiter,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            AuthProperties properties,
            Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenAuthenticator = accessTokenAuthenticator;
        this.passwordPolicy = passwordPolicy;
        this.idempotencyGuard = idempotencyGuard;
        this.registrationRateLimiter = registrationRateLimiter;
        this.loginRateLimiter = loginRateLimiter;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.unknownUserPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public IssuedSession login(LoginRequest request, String remoteAddress) {
        String username = normalizeUsername(request.username());
        String password = requirePassword(request.password(), "password");
        String remote = remote(remoteAddress);

        RateLimitDecision decision = loginRateLimiter.check(username, remote);
        if (!decision.allowed()) {
            throw new RateLimitedException(ErrorCode.USR_RATE_LIMIT_001, decision.retryAfterSeconds());
        }

        UserCredentials credentials = userRepository.findCredentialsByUsername(username).orElse(null);
        String hash = credentials == null ? unknownUserPasswordHash : credentials.passwordHash();
        if (!passwordEncoder.matches(password, hash) || credentials == null) {
            loginRateLimiter.recordFailure(username, remote);
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
        if (credentials.status() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USR_DISABLED_001);
        }

        loginRateLimiter.reset(username, remote);
        userRepository.touchLastLogin(credentials.id(), clock.instant());
        return startSession(credentials.id());
    }

    public IssuedSession register(RegisterRequest request, String idempotencyKey, String remoteAddress) {
        if (!properties.isSelfRegistrationEnabled()) {
            throw new BusinessException(ErrorCode.USR_FORBIDDEN_001, "自助注册已关闭，请联系管理员建号。");
        }
        RateLimitDecision decision = registrationRateLimiter.check(remoteAddress);
        if (!decision.allowed()) {
            throw new RateLimitedException(ErrorCode.COM_RATE_LIMIT_001, decision.retryAfterSeconds());
        }

        String username = normalizeUsername(request.username());
        String password = request.password();
        String key = requireIdempotencyKey(idempotencyKey);
        rejectWeakPassword("password", password);

        String fingerprint = Fingerprints.sha256(
                IdempotencyOperation.REGISTER.name() + '\u0000' + username + '\u0000' + password);
        AtomicReference<IssuedSession> issued = new AtomicReference<>();

        IdempotencyGuard.Outcome<LoginResponse> outcome = idempotencyGuard.run(
                username,
                IdempotencyOperation.REGISTER,
                key,
                fingerprint,
                LoginResponse.class,
                () -> {
                    IssuedSession session = createUser(username, password);
                    issued.set(session);
                    return toResponse(session);
                });

        if (outcome.replayed()) {
            LoginResponse replayed = outcome.response();
            return new IssuedSession(
                    new IssuedToken(replayed.accessToken(), replayed.tokenType(), replayed.expiresIn()),
                    null);
        }
        return issued.get();
    }

    public IssuedSession refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.USR_REFRESH_001);
        }
        RefreshTokenService.IssuedRefreshToken rotated = refreshTokenService.rotate(rawRefreshToken);
        UserCredentials credentials = userRepository.findCredentialsById(rotated.userId()).orElse(null);
        if (credentials == null || credentials.status() != UserStatus.ACTIVE) {
            refreshTokenService.revokeFamily(rotated.familyId());
            throw new BusinessException(ErrorCode.USR_REFRESH_001);
        }
        String accessToken = jwtService.issue(credentials.id(), roleRepository.findRoles(credentials.id()));
        return new IssuedSession(
                new IssuedToken(accessToken, TOKEN_TYPE, jwtService.expiresInSeconds()),
                rotated.value());
    }

    /**
     * 登出对「凭据已失效」保持沉默：访问令牌过期或刷新令牌已被吊销时仍然返回成功，
     * 否则用户无法从失效会话里退出。
     */
    public void logout(String authorizationHeader, String rawRefreshToken) {
        Instant now = clock.instant();
        accessTokenAuthenticator.tryAuthenticate(authorizationHeader)
                .ifPresent(identity -> accessTokenAuthenticator.revoke(identity, now));
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.familyOf(rawRefreshToken)
                    .ifPresent(refreshTokenService::revokeFamily);
        }
    }

    public void changePassword(
            JwtIdentity identity,
            ChangePasswordRequest request,
            String idempotencyKey,
            String remoteAddress) {
        String key = requireIdempotencyKey(idempotencyKey);
        String fingerprint = Fingerprints.sha256(
                IdempotencyOperation.CHANGE_PASSWORD.name()
                        + '\u0000' + request.currentPassword()
                        + '\u0000' + request.newPassword());
        idempotencyGuard.run(
                identity.userId().toString(),
                IdempotencyOperation.CHANGE_PASSWORD,
                key,
                fingerprint,
                Void.class,
                () -> {
                    applyPasswordChange(identity.userId(), request, remote(remoteAddress));
                    return null;
                });
    }

    public CurrentUserData currentUser(JwtIdentity identity) {
        AppUserRow row = userRepository.findById(identity.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USR_AUTH_001));
        if (row.status() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USR_DISABLED_001);
        }
        long remainingSeconds = Math.max(1, Duration.between(clock.instant(), identity.expiresAt()).toSeconds());
        return new CurrentUserData(row.id(), row.username(), row.status(), identity.roles(), remainingSeconds);
    }

    private IssuedSession createUser(String username, String password) {
        if (userRepository.existsByUsername(username)) {
            throw usernameTaken();
        }
        UUID userId = UUID.randomUUID();
        Instant now = clock.instant();
        NewUser user = new NewUser(
                userId,
                username,
                passwordEncoder.encode(password),
                UserStatus.ACTIVE,
                now);
        try {
            userRepository.insert(user, Set.of(UserRole.USER));
        } catch (DuplicateKeyException ex) {
            // 预检查之后仍可能被并发注册抢先落库，唯一键是最后一道防线。
            throw usernameTaken();
        }
        return startSession(userId);
    }

    private IssuedSession startSession(UUID userId) {
        String accessToken = jwtService.issue(userId, roleRepository.findRoles(userId));
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.startFamily(userId);
        return new IssuedSession(
                new IssuedToken(accessToken, TOKEN_TYPE, jwtService.expiresInSeconds()),
                refreshToken.value());
    }

    private void applyPasswordChange(UUID userId, ChangePasswordRequest request, String remote) {
        UserCredentials credentials = userRepository.findCredentialsById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USR_AUTH_001));
        if (credentials.status() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USR_DISABLED_001);
        }

        RateLimitDecision decision = loginRateLimiter.check(credentials.username(), remote);
        if (!decision.allowed()) {
            throw new RateLimitedException(ErrorCode.USR_RATE_LIMIT_001, decision.retryAfterSeconds());
        }
        String currentPassword = request.currentPassword();
        if (currentPassword == null
                || currentPassword.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES
                || !passwordEncoder.matches(currentPassword, credentials.passwordHash())) {
            loginRateLimiter.recordFailure(credentials.username(), remote);
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
        rejectWeakPassword("newPassword", request.newPassword());
        if (passwordEncoder.matches(request.newPassword(), credentials.passwordHash())) {
            throw new BusinessException(
                    ErrorCode.USR_VALIDATION_001,
                    "新口令不能与当前口令相同。",
                    List.of(FieldViolation.of("newPassword", "must differ from the current password")));
        }

        Instant now = clock.instant();
        userRepository.updatePasswordHash(userId, passwordEncoder.encode(request.newPassword()), now);
        // 改密通常正因为怀疑凭据泄露：其他设备上的在途 access 与刷新令牌都必须一并作废。
        refreshTokenService.revokeAllForUser(userId);
        accessTokenAuthenticator.revokeAllIssuedBefore(userId, now);
        loginRateLimiter.reset(credentials.username(), remote);
    }

    private LoginResponse toResponse(IssuedSession session) {
        IssuedToken access = session.access();
        return new LoginResponse(access.accessToken(), access.tokenType(), access.expiresIn());
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw validationError("username", "must not be blank");
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String requirePassword(String password, String field) {
        if (password == null || password.isBlank()) {
            throw validationError(field, "must not be blank");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw validationError(field, "must not exceed 72 UTF-8 bytes");
        }
        return password;
    }

    private void rejectWeakPassword(String field, String password) {
        List<FieldViolation> violations = passwordPolicy.violations(field, password);
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.USR_VALIDATION_001, null, violations);
        }
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() < IDEMPOTENCY_KEY_MIN_LENGTH
                || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw validationError("Idempotency-Key", "must be 8-64 non-blank characters");
        }
        return idempotencyKey;
    }

    private String remote(String remoteAddress) {
        return remoteAddress == null ? "" : remoteAddress;
    }

    private BusinessException usernameTaken() {
        return new BusinessException(
                ErrorCode.USR_CONFLICT_001,
                null,
                List.of(FieldViolation.of("username", "already taken")));
    }

    private BusinessException validationError(String field, String message) {
        return new BusinessException(
                ErrorCode.COM_VALIDATION_001,
                null,
                List.of(FieldViolation.of(field, message)));
    }
}
