package com.docqa.reminder.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.reminder.service.ReminderTriggerIngestService;
import java.io.IOException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 解析与落库之间的边界。日志只输出 {@code reminderId} 与 {@code traceId}：载荷的
 * {@code title}/{@code description} 是用户文本快照，属于 PII，不得进入日志（协议 §6.5）。
 */
@Component
public class ReminderDueMessageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReminderDueMessageHandler.class);

    private final ObjectMapper objectMapper;
    private final ReminderTriggerIngestService ingestService;

    public ReminderDueMessageHandler(ObjectMapper objectMapper, ReminderTriggerIngestService ingestService) {
        this.objectMapper = objectMapper;
        this.ingestService = ingestService;
    }

    public ConsumeResult handle(byte[] body, String messageId) {
        if (body == null || body.length == 0) {
            LOGGER.error("Rejecting empty reminder due message; it will be retried and then dead-lettered");
            return ConsumeResult.RETRY;
        }
        ReminderDueEvent event;
        try {
            event = objectMapper.readValue(body, ReminderDueEvent.class);
        } catch (IOException ex) {
            LOGGER.error(
                    "Rejecting malformed reminder due message; it will be retried and then dead-lettered: reason={}",
                    ex.getClass().getSimpleName());
            return ConsumeResult.RETRY;
        }
        try {
            return withTrace(event.traceId(), () -> {
                ingestService.ingest(event, messageId);
                return ConsumeResult.ACK;
            });
        } catch (ReminderDueRejectedException ex) {
            LOGGER.error(
                    "Rejecting invalid reminder due event; it will be retried and then dead-lettered: "
                            + "reminderId={}, traceId={}, reason={}",
                    reminderId(event),
                    event.traceId(),
                    ex.getMessage());
            return ConsumeResult.RETRY;
        } catch (RuntimeException ex) {
            LOGGER.error(
                    "Reminder trigger ingestion failed; scheduling retry: reminderId={}, traceId={}",
                    reminderId(event),
                    event.traceId(),
                    ex);
            return ConsumeResult.RETRY;
        }
    }

    private String reminderId(ReminderDueEvent event) {
        return event.reminderId() == null ? "unknown" : event.reminderId().toString();
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
