package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.UUID;

public record AiQuota(
        UUID userId,
        String username,
        Long dailyQuestionLimit,
        Long dailyTokenLimit,
        Instant updatedAt,
        UUID updatedBy) {
}
