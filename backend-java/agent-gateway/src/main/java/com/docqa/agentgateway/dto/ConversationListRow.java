package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationListRow(
        UUID id,
        String title,
        List<UUID> documentScopeIds,
        Instant lastMessageAt,
        Instant createdAt) {
}
