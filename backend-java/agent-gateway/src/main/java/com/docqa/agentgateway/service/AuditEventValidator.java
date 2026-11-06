package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AuditEvent;
import com.docqa.agentgateway.dto.PythonAuditEvent;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AuditEventValidator {

    public static final int MAX_EVENTS = 50;

    private static final int MAX_PAYLOAD_STRING = 256;
    private static final int MAX_PAYLOAD_LIST = 10;
    private static final int MAX_LIST_STRING = 64;
    private static final Pattern EVENT_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "durationMs",
            "tokenCount",
            "toolName",
            "toolSource",
            "chunkCount",
            "topScore",
            "round",
            "confidence",
            "budgetStatus",
            "errorCode",
            "maskingVersion",
            "piiCategories",
            "piiCount",
            "model",
            "cacheHit",
            "historyTruncated",
            "targetUserId");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "ERROR");

    private final AuditEventFactory eventFactory;

    public AuditEventValidator(AuditEventFactory eventFactory) {
        this.eventFactory = eventFactory;
    }

    public List<AuditEvent> validateResponse(
            List<PythonAuditEvent> events,
            String traceId,
            UUID userId,
            UUID conversationId) {
        if (events == null || events.isEmpty() || events.size() > MAX_EVENTS) {
            throw protocolViolation("auditEvents must contain 1 to 50 events");
        }
        List<AuditEvent> validated = new ArrayList<>();
        Set<UUID> eventIds = new HashSet<>();
        boolean payloadRejected = false;
        for (PythonAuditEvent event : events) {
            validateRequiredFields(event, eventIds);
            PayloadFilter filter = filterPayload(event.payloadRedacted());
            payloadRejected = payloadRejected || filter.rejected();
            validated.add(toAuditEvent(event, traceId, userId, conversationId, filter.payload()));
        }
        if (payloadRejected) {
            validated.add(eventFactory.payloadRejected(traceId, userId, conversationId));
        }
        return validated;
    }

    public AuditValidation validateError(
            List<PythonAuditEvent> events,
            String traceId,
            UUID userId,
            UUID conversationId) {
        if (events == null || events.isEmpty()) {
            return new AuditValidation(List.of(), false);
        }
        List<AuditEvent> validated = new ArrayList<>();
        Set<UUID> eventIds = new HashSet<>();
        boolean gap = events.size() > MAX_EVENTS;
        boolean payloadRejected = false;
        for (PythonAuditEvent event : events.stream().limit(MAX_EVENTS).toList()) {
            if (!isValidRequiredFields(event, eventIds)) {
                gap = true;
                continue;
            }
            PayloadFilter filter = filterPayload(event.payloadRedacted());
            payloadRejected = payloadRejected || filter.rejected();
            validated.add(toAuditEvent(event, traceId, userId, conversationId, filter.payload()));
        }
        if (payloadRejected) {
            validated.add(eventFactory.payloadRejected(traceId, userId, conversationId));
        }
        return new AuditValidation(validated, gap);
    }

    private void validateRequiredFields(PythonAuditEvent event, Set<UUID> eventIds) {
        if (!isValidRequiredFields(event, eventIds)) {
            throw protocolViolation("audit event is missing required fields or has an invalid shape");
        }
    }

    private boolean isValidRequiredFields(PythonAuditEvent event, Set<UUID> eventIds) {
        if (event == null
                || event.eventId() == null
                || !eventIds.add(event.eventId())
                || event.eventType() == null
                || !EVENT_TYPE.matcher(event.eventType()).matches()
                || event.agentName() == null
                || event.agentName().isBlank()
                || event.agentName().length() > 64
                || event.severity() == null
                || !SEVERITIES.contains(event.severity())
                || event.occurredAt() == null) {
            return false;
        }
        return true;
    }

    private AuditEvent toAuditEvent(
            PythonAuditEvent event,
            String traceId,
            UUID userId,
            UUID conversationId,
            Map<String, Object> payload) {
        return new AuditEvent(
                event.eventId(),
                event.eventType(),
                event.agentName(),
                "PYTHON",
                event.severity(),
                event.occurredAt(),
                traceId,
                userId,
                conversationId,
                payload);
    }

    private PayloadFilter filterPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return new PayloadFilter(Map.of(), false);
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        boolean rejected = false;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!ALLOWED_KEYS.contains(entry.getKey())) {
                rejected = true;
                continue;
            }
            Object value = sanitize(entry.getValue());
            if (value == null) {
                rejected = true;
                continue;
            }
            filtered.put(entry.getKey(), value);
        }
        return new PayloadFilter(Map.copyOf(filtered), rejected);
    }

    private Object sanitize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            return stringValue.length() <= MAX_PAYLOAD_STRING
                    ? stringValue
                    : stringValue.substring(0, MAX_PAYLOAD_STRING);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<String> sanitized = list.stream()
                    .limit(MAX_PAYLOAD_LIST)
                    .map(item -> item == null ? "" : item.toString())
                    .map(item -> item.length() <= MAX_LIST_STRING ? item : item.substring(0, MAX_LIST_STRING))
                    .toList();
            return sanitized;
        }
        return null;
    }

    private BusinessException protocolViolation(String detail) {
        return new BusinessException(ErrorCode.AGT_UPSTREAM_004, detail);
    }

    private record PayloadFilter(Map<String, Object> payload, boolean rejected) {
    }
}
