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
            // 空消息是确定性失败：重试再多次也不会变得可解析。直接 ACK 并以 ERROR
            // 留痕，避免占用 maxReconsumeTimes（16 次）的 broker 资源。
            LOGGER.error("Discarding empty audit event message (poison message)");
            return ConsumeResult.ACK;
        }
        AuditEventMessage message;
        try {
            message = objectMapper.readValue(body, AuditEventMessage.class);
        } catch (IOException ex) {
            // 反序列化失败同理是毒消息，重试无意义；ERROR 日志保留排查线索。
            LOGGER.error(
                    "Discarding malformed audit event (poison message): reason={}",
                    ex.getClass().getSimpleName());
            return ConsumeResult.ACK;
        }
        String traceId = resolveTraceId(traceIdProperty, message.traceId());
        try {
            return withTrace(traceId, () -> {
                auditEventService.ingest(message, traceIdProperty);
                return ConsumeResult.ACK;
            });
        } catch (AuditEventRejectedException ex) {
            // 字段校验失败同样是确定性失败（重试不会让缺失的 eventId 出现），
            // 直接 ACK + ERROR 留痕；只有下游 RuntimeException 才真正需要重试。
            LOGGER.error(
                    "Discarding invalid audit event (poison message): eventId={}, reason={}",
                    eventId(message),
                    ex.getMessage());
            return ConsumeResult.ACK;
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
