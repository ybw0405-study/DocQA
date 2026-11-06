package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.UUID;

public record MessageHistoryRow(
        UUID id,
        String role,
        String content,
        int tokenCount,
        String traceId,
        Instant createdAt) {
}
