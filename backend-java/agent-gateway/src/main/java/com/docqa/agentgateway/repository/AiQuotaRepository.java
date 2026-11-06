package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.AiQuota;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiQuotaRepository {

    Optional<String> findUsername(UUID userId);

    Optional<AiQuota> findByUserId(UUID userId);

    List<AiQuota> findPage(Instant beforeUpdatedAt, UUID beforeUserId, int limit);

    void upsert(UUID userId, Long dailyQuestionLimit, Long dailyTokenLimit, UUID updatedBy, Instant now);

    void delete(UUID userId);
}
