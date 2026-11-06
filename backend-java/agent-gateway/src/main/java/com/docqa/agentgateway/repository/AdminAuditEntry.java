package com.docqa.agentgateway.repository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AdminAuditEntry(
        UUID id,
        String traceId,
        UUID userId,
        String eventType,
        String severity,
        Map<String, Object> payload,
        Instant occurredAt,
        Instant ingestedAt) {
}
