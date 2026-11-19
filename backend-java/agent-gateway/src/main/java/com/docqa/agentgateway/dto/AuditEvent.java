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
        Map<String, Object> payloadRedacted,
        UUID tenantId) {

    /**
     * outbox 行的 {@code tenant_id} 才是租户归属的权威来源；工厂生成事件时尚未补写，
     * 由 relay 在投递前用这个方法补上。
     */
    public AuditEvent withTenant(UUID tenantId) {
        return new AuditEvent(
                eventId, eventType, agentName, source, severity, occurredAt, traceId,
                userId, conversationId, payloadRedacted, tenantId);
    }
}
