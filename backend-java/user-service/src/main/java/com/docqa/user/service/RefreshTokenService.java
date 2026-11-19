package com.docqa.user.service;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.config.AuthProperties;
import com.docqa.user.domain.RefreshTokenRow;
import com.docqa.user.domain.RefreshTokenStatus;
import com.docqa.user.repository.RefreshTokenRepository;
import com.docqa.user.support.Fingerprints;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 刷新令牌的一次使用即轮换。库里只存 SHA-256 摘要，明文只在 Set-Cookie 里出现一次。
 *
 * <p>租户边界（P1）：家族由登录时解析出的 tenantId 建立；refresh / logout 没有 JWT，
 * 先从令牌摘要控制面反查 tenantId（凭据自带作用域），再用它给所有读写加 tenant_id 谓词。
 * 这样第二租户的刷新令牌既不能续出默认租户的令牌，也不会吊销别的租户的家族。
 */
@Service
public class RefreshTokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int RANDOM_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 登录/注册成功后建立一个新的轮换家族，绑定该会话的租户。 */
    @Transactional
    public IssuedRefreshToken startFamily(UUID tenantId, UUID userId) {
        return insert(tenantId, userId, UUID.randomUUID());
    }

    /**
     * 续期：占用旧行 -> 插入同家族新行。占用失败或命中终态一律按复用处理并吊销整家族，
     * 服务端无法区分「并发双击」与「被盗复用」，宁可让两端都重新登录。
     */
    @Transactional
    public IssuedRefreshToken rotate(String rawToken) {
        Instant now = clock.instant();
        String hash = Fingerprints.sha256(rawToken);
        UUID tenantId = repository.findTenantIdByHash(hash).orElseThrow(RefreshTokenService::rejected);
        RefreshTokenRow row = repository.findByHash(tenantId, hash).orElseThrow(RefreshTokenService::rejected);
        if (row.status() != RefreshTokenStatus.ACTIVE) {
            revokeFamilyAndReject(tenantId, row);
        }
        if (!row.expiresAt().isAfter(now)) {
            throw rejected();
        }
        if (repository.consumeActive(tenantId, row.id(), now) != 1) {
            revokeFamilyAndReject(tenantId, row);
        }
        return insert(tenantId, row.userId(), row.familyId());
    }

    public void revokeFamily(UUID tenantId, UUID familyId) {
        int revoked = repository.revokeFamily(tenantId, familyId, clock.instant());
        LOGGER.info("Revoked refresh token family: tenantId={} familyId={} count={}", tenantId, familyId, revoked);
    }

    public void revokeAllForUser(UUID tenantId, UUID userId) {
        int revoked = repository.revokeAllActiveForUser(tenantId, userId, clock.instant());
        LOGGER.info("Revoked all refresh tokens for user: tenantId={} count={}", tenantId, revoked);
    }

    /** 登出用：只凭 Cookie 解析出 (tenantId, familyId)，随后由调用方按租户吊销。 */
    public Optional<TokenFamily> familyOf(String rawToken) {
        String hash = Fingerprints.sha256(rawToken);
        return repository.findTenantIdByHash(hash)
                .flatMap(tenantId -> repository.findByHash(tenantId, hash)
                        .map(row -> new TokenFamily(tenantId, row.familyId())));
    }

    public long cookieMaxAgeSeconds() {
        return properties.getRefreshTtl().toSeconds();
    }

    private void revokeFamilyAndReject(UUID tenantId, RefreshTokenRow row) {
        revokeFamily(tenantId, row.familyId());
        throw rejected();
    }

    private IssuedRefreshToken insert(UUID tenantId, UUID userId, UUID familyId) {
        Instant now = clock.instant();
        String raw = nextRawToken();
        RefreshTokenRow row = new RefreshTokenRow(
                UUID.randomUUID(),
                userId,
                familyId,
                Fingerprints.sha256(raw),
                RefreshTokenStatus.ACTIVE,
                now.plus(properties.getRefreshTtl()),
                now);
        repository.insert(tenantId, row);
        return new IssuedRefreshToken(raw, tenantId, row.userId(), familyId, row.expiresAt());
    }

    private String nextRawToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static BusinessException rejected() {
        return new BusinessException(ErrorCode.USR_REFRESH_001);
    }

    public record IssuedRefreshToken(
            String value, UUID tenantId, UUID userId, UUID familyId, Instant expiresAt) {
    }

    public record TokenFamily(UUID tenantId, UUID familyId) {
    }
}
