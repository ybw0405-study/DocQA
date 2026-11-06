package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AskData(
        UUID conversationId,
        UUID messageId,
        String answer,
        List<Citation> citations,
        double confidence,
        String budgetStatus,
        int tokenUsed,
        int toolCallsMade,
        int roundsUsed,
        boolean cacheHit,
        boolean idempotentReplay,
        Instant createdAt) {
}
