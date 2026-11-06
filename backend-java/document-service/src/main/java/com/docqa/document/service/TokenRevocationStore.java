package com.docqa.document.service;

import java.time.Instant;
import java.util.UUID;

/**
 * 访问令牌吊销查询。吊销事实由 user-service 写入，本服务只读：
 * 刷新令牌吊销以数据库为准，这里的 Redis 标记只负责让在途 access 提前失效。
 */
public interface TokenRevocationStore {

    /** 单个 `jti` 被登出吊销，或签发时刻早于该用户的吊销水位时返回 true。 */
    boolean isRevoked(String tokenId, UUID userId, Instant issuedAt);
}
