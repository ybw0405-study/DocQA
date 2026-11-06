package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationData(
        UUID conversationId,
        String title,
        List<UUID> documentScopeIds,
        boolean idempotentReplay,
        Instant createdAt) {
}
