package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationListItemData(
        UUID conversationId,
        String title,
        List<UUID> documentScopeIds,
        Instant lastMessageAt,
        Instant createdAt) {

    public ConversationListItemData {
        documentScopeIds = documentScopeIds == null ? List.of() : List.copyOf(documentScopeIds);
    }
}
