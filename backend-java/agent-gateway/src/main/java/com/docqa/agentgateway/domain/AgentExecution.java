package com.docqa.agentgateway.domain;

import com.docqa.agentgateway.dto.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentExecution(
        UUID id,
        UUID tenantId,
        String traceId,
        UUID userId,
        UUID conversationId,
        UUID requestMessageId,
        UUID responseMessageId,
        String status,
        String budgetStatus,
        int tokenBudget,
        int tokenUsed,
        int toolCallsMade,
        int roundsUsed,
        boolean cacheHit,
        long latencyMs,
        String errorCode,
        List<AuditEvent> auditOutbox,
        Instant startedAt,
        Instant finishedAt,
        Instant recordedAt) {
}
