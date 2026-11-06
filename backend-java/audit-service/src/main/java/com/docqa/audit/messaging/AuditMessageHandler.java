package com.docqa.audit.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.audit.dto.AuditEventMessage;
import com.docqa.audit.service.AuditEventRejectedException;
import com.docqa.audit.service.AuditEventService;
import com.docqa.common.trace.TraceIdContext;
import java.io.IOException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuditMessageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditMessageHandler.class);

    private final ObjectMapper objectMapper;
    private final AuditEventService auditEventService;

    public AuditMessageHandler(ObjectMapper objectMapper, AuditEventService auditEventService) {
        this.objectMapper = objectMapper;
        this.auditEventService = auditEventService;
    }

    public ConsumeResult handle(byte[] body, String traceIdProperty) {
        if (body == null || body.length == 0) {
            LOGGER.error("Rejecting empty audit event message; it will be retried and then dead-lettered");
            return ConsumeResult.RETRY;
        }
        AuditEventMessage message;
        try {
            message = objectMapper.readValue(body, AuditEventMessage.class);
        } catch (IOException ex) {
            LOGGER.error(
                    "Rejecting malformed audit event; it will be retried and then dead-lettered: reason={}",
                    ex.getClass().getSimpleName());
            return ConsumeResult.RETRY;
        }
        String traceId = resolveTraceId(traceIdProperty, message.traceId());
        try {
            return withTrace(traceId, () -> {
                auditEventService.ingest(message, traceIdProperty);
                return ConsumeResult.ACK;
            });
        } catch (AuditEventRejectedException ex) {
            LOGGER.error(
                    "Rejecting invalid audit event; it will be retried and then dead-lettered: eventId={}, reason={}",
                    eventId(message),
                    ex.getMessage());
            return ConsumeResult.RETRY;
        } catch (RuntimeException ex) {
            LOGGER.error("Audit event ingestion failed; scheduling retry: eventId={}", eventId(message), ex);
            return ConsumeResult.RETRY;
        }
    }

    private String resolveTraceId(String traceIdProperty, String payloadTraceId) {
        if (TraceIdContext.isValid(traceIdProperty)) {
            return traceIdProperty;
        }
        return TraceIdContext.isValid(payloadTraceId) ? payloadTraceId : null;
    }

    private String eventId(AuditEventMessage message) {
        return message.eventId() == null ? "unknown" : message.eventId().toString();
    }

    private ConsumeResult withTrace(String traceId, Supplier<ConsumeResult> action) {
        String previous = TraceIdContext.current();
        try {
            if (TraceIdContext.isValid(traceId)) {
                TraceIdContext.set(traceId);
            }
            return action.get();
        } finally {
            if (TraceIdContext.isValid(previous)) {
                TraceIdContext.set(previous);
            } else {
                TraceIdContext.clear();
            }
        }
    }
}
