package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AskData;
import com.docqa.agentgateway.dto.AuthorizedDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ask 与 askStream 共享的管线前段产物：校验、限流、会话锁、幂等查重、配额、PII 脱敏、
 * 历史装载与缓存探测全部完成后冻结于此，两种传输形态（同步 JSON / SSE）复用同一段治理逻辑。
 */
public record PreparedAsk(
        UUID userId,
        UUID conversationId,
        String key,
        String fingerprint,
        String question,
        List<UUID> documentIds,
        List<AuthorizedDocument> documents,
        List<QaCacheService.PythonHistoryMessageMask> history,
        PiiMaskingResult masked,
        String traceId,
        Instant startedAt,
        boolean lockHeld,
        boolean stream,
        Optional<AskData> replay,
        Optional<CachedAnswer> cached) {

    public boolean isFresh() {
        return replay.isEmpty() && cached.isEmpty();
    }
}
