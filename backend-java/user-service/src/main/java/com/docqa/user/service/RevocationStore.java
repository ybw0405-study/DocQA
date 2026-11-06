package com.docqa.user.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 访问令牌撤销表。数据库里的刷新令牌吊销才是权威事实，本存储只负责让在途 access 立即失效，
 * 因此读取失败时按「未撤销」处理（fail-open）并告警，写入失败时不得回滚数据库吊销。
 */
public interface RevocationStore {

    void revokeAccessToken(String tokenId, Duration remainingLifetime);

    /** watermark 是「签发时刻下界」：早于它的 access 一律视为已撤销。 */
    void revokeUser(UUID userId, Instant watermark, Duration ttl);

    boolean isAccessTokenRevoked(String tokenId);

    /** 返回该用户的签发时刻下界；早于它的 access 一律拒绝。 */
    Optional<Instant> issuedAtFloor(UUID userId);
}
