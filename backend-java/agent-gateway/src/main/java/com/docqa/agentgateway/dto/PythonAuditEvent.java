package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PythonAuditEvent(
        UUID eventId,
        String eventType,
        String agentName,
        String severity,
        Instant occurredAt,
        Map<String, Object> payloadRedacted) {
}
