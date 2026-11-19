package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.AiQuota;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiQuotaRepository {

    Optional<String> findUsername(UUID tenantId, UUID userId);

    Optional<AiQuota> findByUserId(UUID tenantId, UUID userId);

    List<AiQuota> findPage(UUID tenantId, Instant beforeUpdatedAt, UUID beforeUserId, int limit);

    void upsert(UUID tenantId, UUID userId, Long dailyQuestionLimit, Long dailyTokenLimit, UUID updatedBy,
            Instant now);

    void delete(UUID tenantId, UUID userId);
}
