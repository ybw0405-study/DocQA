package com.docqa.audit.service;

import com.docqa.audit.config.AuditProperties;
import com.docqa.audit.domain.AuditEventRow;
import com.docqa.audit.dto.AuditEventMessage;
import com.docqa.common.trace.TraceIdContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AuditEventNormalizer {

    static final Pattern EVENT_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    static final Pattern AGENT_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    private static final Set<String> SOURCES = Set.of("JAVA", "PYTHON");
    private static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "ERROR");

    private final AuditPayloadSanitizer sanitizer;
    private final AuditProperties properties;

    public AuditEventNormalizer(AuditPayloadSanitizer sanitizer, AuditProperties properties) {
        this.sanitizer = sanitizer;
        this.properties = properties;
    }

    public NormalizedAuditEvent normalize(
            AuditEventMessage message,
            String traceIdProperty,
            Instant now) {
        if (message == null) {
            throw new AuditEventRejectedException("missing audit event");
        }
        if (message.eventId() == null) {
            throw new AuditEventRejectedException("missing eventId");
        }
        if (message.userId() == null) {
            throw new AuditEventRejectedException("missing userId");
        }
        if (message.occurredAt() == null) {
            throw new AuditEventRejectedException("missing occurredAt");
        }
        String eventType = requireEventType(message.eventType());
        String source = requireSource(message.source());
        String traceId = resolveTraceId(traceIdProperty, message.traceId());

        AuditPayloadSanitizer.SanitizedPayload sanitized = sanitizer.sanitize(message.payloadRedacted());
        Instant occurredAt = clampOccurredAt(message.occurredAt(), now);
        String severity = normalizeSeverity(message.severity());
        String agentName = normalizeAgentName(message.agentName());

        AuditEventRow event = new AuditEventRow(
                message.eventId(),
                traceId,
                message.userId(),
                eventType,
                agentName,
                source,
                severity,
                sanitized.payload(),
                occurredAt,
                now);

        List<String> warnings = new ArrayList<>(sanitized.droppedFields());
        if (agentName == null && hasText(message.agentName())) {
            warnings.add("agentName");
        }
        if (message.severity() != null && !severity.equals(message.severity())) {
            warnings.add("severity");
        }
        if (!occurredAt.equals(message.occurredAt())) {
            warnings.add("occurredAt");
        }
        return new NormalizedAuditEvent(event, List.copyOf(warnings));
    }

    private String requireEventType(String eventType) {
        if (eventType == null || !EVENT_TYPE.matcher(eventType.trim()).matches()) {
            throw new AuditEventRejectedException("invalid eventType");
        }
        return eventType.trim();
    }

    private String requireSource(String source) {
        if (source == null) {
            throw new AuditEventRejectedException("missing source");
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(normalized)) {
            throw new AuditEventRejectedException("invalid source");
        }
        return normalized;
    }

    private String resolveTraceId(String traceIdProperty, String payloadTraceId) {
        if (TraceIdContext.isValid(traceIdProperty)) {
            return traceIdProperty;
        }
        if (TraceIdContext.isValid(payloadTraceId)) {
            return payloadTraceId;
        }
        throw new AuditEventRejectedException("invalid traceId");
    }

    private String normalizeSeverity(String severity) {
        if (severity == null) {
            return "INFO";
        }
        String normalized = severity.trim().toUpperCase(Locale.ROOT);
        return SEVERITIES.contains(normalized) ? normalized : "INFO";
    }

    private String normalizeAgentName(String agentName) {
        if (agentName == null) {
            return null;
        }
        String normalized = agentName.trim();
        if (!AGENT_NAME.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    private Instant clampOccurredAt(Instant occurredAt, Instant now) {
        Instant latest = now.plus(properties.getOccurredAtSkew());
        return occurredAt.isAfter(latest) ? now : occurredAt;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record NormalizedAuditEvent(AuditEventRow event, List<String> warnings) {
    }
}
