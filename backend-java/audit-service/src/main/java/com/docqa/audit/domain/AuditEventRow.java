package com.docqa.audit.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventRow(
        UUID id,
        String traceId,
        UUID userId,
        String eventType,
        String agentName,
        String source,
        String severity,
        Map<String, Object> payloadRedacted,
        Instant occurredAt,
        Instant ingestedAt) {
}
