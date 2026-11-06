package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AuditEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AuditEventFactory {

    public static final String AGENT_NAME = "agent_gateway";

    private final Clock clock;

    public AuditEventFactory(Clock clock) {
        this.clock = clock;
    }

    public AuditEvent completed(
            String traceId,
            UUID userId,
            UUID conversationId,
            long durationMs,
            int tokenCount,
            String budgetStatus,
            boolean cacheHit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("durationMs", durationMs);
        payload.put("tokenCount", tokenCount);
        payload.put("budgetStatus", budgetStatus);
        payload.put("cacheHit", cacheHit);
        return create("AGENT_ASK_COMPLETED", "INFO", traceId, userId, conversationId, payload);
    }

    public AuditEvent failed(
            String traceId,
            UUID userId,
            UUID conversationId,
            long durationMs,
            String errorCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("durationMs", durationMs);
        payload.put("errorCode", errorCode);
        return create("AGENT_ASK_FAILED", "ERROR", traceId, userId, conversationId, payload);
    }

    public AuditEvent cacheHit(String traceId, UUID userId, UUID conversationId) {
        return create(
                "AGENT_CACHE_HIT",
                "INFO",
                traceId,
                userId,
                conversationId,
                Map.of("cacheHit", true));
    }

    public AuditEvent killSwitch(String traceId, UUID userId, UUID conversationId) {
        return create(
                "AGENT_KILL_SWITCH",
                "WARN",
                traceId,
                userId,
                conversationId,
                Map.of("errorCode", "AGT-UPSTREAM-003"));
    }

    public AuditEvent protocolViolation(String traceId, UUID userId, UUID conversationId, String detail) {
        return create(
                "AGENT_PROTOCOL_VIOLATION",
                "ERROR",
                traceId,
                userId,
                conversationId,
                Map.of("errorCode", "AGT-UPSTREAM-004"));
    }

    public AuditEvent auditGap(String traceId, UUID userId, UUID conversationId, String errorCode) {
        return create(
                "AUDIT_GAP",
                "ERROR",
                traceId,
                userId,
                conversationId,
                Map.of("errorCode", errorCode));
    }

    public AuditEvent payloadRejected(String traceId, UUID userId, UUID conversationId) {
        return create(
                "AUDIT_PAYLOAD_REJECTED",
                "WARN",
                traceId,
                userId,
                conversationId,
                Map.of("errorCode", "AUDIT_PAYLOAD_REJECTED"));
    }

    public AuditEvent piiBlocked(
            String traceId,
            UUID userId,
            UUID conversationId,
            int piiCount,
            List<String> categories) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("maskingVersion", PiiMasker.VERSION);
        payload.put("piiCount", piiCount);
        payload.put("piiCategories", categories);
        return create("PII_LEAK_BLOCKED", "WARN", traceId, userId, conversationId, payload);
    }

    public AuditEvent create(
            String eventType,
            String severity,
            String traceId,
            UUID userId,
            UUID conversationId,
            Map<String, Object> payload) {
        return new AuditEvent(
                UUID.randomUUID(),
                eventType,
                AGENT_NAME,
                "JAVA",
                severity,
                clock.instant(),
                traceId,
                userId,
                conversationId,
                Map.copyOf(payload));
    }

    public Instant now() {
        return clock.instant();
    }
}
