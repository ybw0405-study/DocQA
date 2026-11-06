package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEvent(
        UUID eventId,
        String eventType,
        String agentName,
        String source,
        String severity,
        Instant occurredAt,
        String traceId,
        UUID userId,
        UUID conversationId,
        Map<String, Object> payloadRedacted) {
}
