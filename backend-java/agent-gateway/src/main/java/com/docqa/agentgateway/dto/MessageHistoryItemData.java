package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.UUID;

public record MessageHistoryItemData(
        UUID messageId,
        String role,
        String content,
        int tokenCount,
        String traceId,
        Instant createdAt) {
}
