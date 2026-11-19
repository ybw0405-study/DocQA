package com.docqa.user.repository;

import com.docqa.user.domain.RefreshTokenRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    void insert(UUID tenantId, RefreshTokenRow token);

    Optional<RefreshTokenRow> findByHash(UUID tenantId, String tokenHash);

    /**
     * 控制面查询：只用刷新令牌摘要反查它所属的租户。
     *
     * <p>refresh 与 logout 只有 Cookie、没有 JWT，必须先确定 tenantId 才能调用带租户谓词的方法。
     * token_hash 是 256 位随机值的摘要，本身就是凭据；这里不返回任何业务数据，只返回 tenant_id，
     * 后续所有读写仍由 {@link #findByHash} / consume / revoke 的 tenant_id 谓词收口。
     * 这是唯一允许不带 tenant_id 谓词的业务表查询，理由是「凭据自带作用域」。
     */
    Optional<UUID> findTenantIdByHash(String tokenHash);

    /** 轮换的第一步：只有仍为 ACTIVE 的行才能被本次请求占用，返回 0 表示摘要已被使用或已吊销。 */
    int consumeActive(UUID tenantId, UUID id, Instant now);

    int revokeFamily(UUID tenantId, UUID familyId, Instant now);

    int revokeAllActiveForUser(UUID tenantId, UUID userId, Instant now);

    /**
     * 清理终态且已过期的行，跨租户运维任务，不按单租户过滤：
     * 清理游标是全局的 expires_at 索引，漏掉任何租户的行都会留下永不回收的令牌。
     */
    int deleteExpiredTerminal(Instant now, int limit);
}
