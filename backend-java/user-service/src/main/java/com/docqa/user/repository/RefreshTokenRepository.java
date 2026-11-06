package com.docqa.user.repository;

import com.docqa.user.domain.RefreshTokenRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

    void insert(RefreshTokenRow token);

    Optional<RefreshTokenRow> findByHash(String tokenHash);

    /** 轮换的第一步：只有仍为 ACTIVE 的行才能被本次请求占用，返回 0 表示摘要已被使用或已吊销。 */
    int consumeActive(UUID id, Instant now);

    int revokeFamily(UUID familyId, Instant now);

    int revokeAllActiveForUser(UUID userId, Instant now);

    /** 清理终态且已过期的行，返回删除数；expires_at 索引服务这条扫描。 */
    int deleteExpiredTerminal(Instant now, int limit);
}
