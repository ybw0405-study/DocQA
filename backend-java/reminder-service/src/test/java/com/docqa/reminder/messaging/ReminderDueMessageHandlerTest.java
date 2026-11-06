package com.docqa.reminder.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.reminder.service.ReminderTriggerIngestService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 解析与落库之间的边界：消费者无法写成一行的一律交还给 RocketMQ 重投，而不是静默丢弃——
 * 通知行是用户唯一能看见「提醒确实响过」的证据，丢掉就再也补不回来。
 */
@ExtendWith(MockitoExtension.class)
class ReminderDueMessageHandlerTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String MESSAGE_ID = "0A1B2C3D4E5F";

    @Mock
    private ReminderTriggerIngestService ingestService;

    private ReminderDueMessageHandler handler;

    @BeforeEach
    void setUp() {
        // 与生产同配置：spring.jackson.deserialization.fail-on-unknown-properties=true（application.yml:15）。
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        handler = new ReminderDueMessageHandler(objectMapper, ingestService);
    }

    @AfterEach
    void clearTrace() {
        TraceIdContext.clear();
    }

    @Test
    void validEventIsAcknowledgedAndForwardedWithTheTransportMessageId() {
        ConsumeResult result = handler.handle(body(TRACE_ID), MESSAGE_ID);

        assertThat(result).isEqualTo(ConsumeResult.ACK);
        verify(ingestService).ingest(any(ReminderDueEvent.class), eq(MESSAGE_ID));
    }

    @Test
    void emptyBodyIsRetriedSoItEventuallyReachesTheDeadLetterQueue() {
        assertThat(handler.handle(new byte[0], MESSAGE_ID)).isEqualTo(ConsumeResult.RETRY);
        assertThat(handler.handle(null, MESSAGE_ID)).isEqualTo(ConsumeResult.RETRY);

        verifyNoInteractions(ingestService);
    }

    @Test
    void malformedJsonIsRetriedRatherThanDropped() {
        assertThat(handler.handle("{not-json".getBytes(StandardCharsets.UTF_8), MESSAGE_ID))
                .isEqualTo(ConsumeResult.RETRY);

        verifyNoInteractions(ingestService);
    }

    @Test
    void aFieldOutsideTheRegisteredEventSchemaIsRetriedNotIgnored() {
        // 协议 §6.3 的演进规则「消费者先行、生产者后发」正是靠这条严格性生效：
        // 生产者不能悄悄加宽线上载荷，否则未升级的消费者会整批进入 DLQ。
        String json = new String(body(TRACE_ID), StandardCharsets.UTF_8);
        byte[] widened = (json.substring(0, json.length() - 1) + ",\"leadMinutes\":10}")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(handler.handle(widened, MESSAGE_ID)).isEqualTo(ConsumeResult.RETRY);

        verifyNoInteractions(ingestService);
    }

    @Test
    void anEventTheIngestServiceRejectsIsRetriedSoTheOperatorSeesIt() {
        doThrow(new ReminderDueRejectedException("traceId must be 32 lowercase hex characters"))
                .when(ingestService).ingest(any(), eq(MESSAGE_ID));

        assertThat(handler.handle(body(TRACE_ID), MESSAGE_ID)).isEqualTo(ConsumeResult.RETRY);
    }

    @Test
    void aStorageFailureIsRetriedInsteadOfLosingTheNotification() {
        doThrow(new IllegalStateException("database down"))
                .when(ingestService).ingest(any(), eq(MESSAGE_ID));

        assertThat(handler.handle(body(TRACE_ID), MESSAGE_ID)).isEqualTo(ConsumeResult.RETRY);
    }

    @Test
    void thePayloadTraceIdIsExposedDuringHandlingAndTheAmbientOneIsRestoredAfterwards() {
        TraceIdContext.set(TRACE_ID);
        String[] seen = new String[1];
        doAnswer(invocation -> {
            seen[0] = TraceIdContext.current();
            return null;
        }).when(ingestService).ingest(any(), eq(MESSAGE_ID));

        handler.handle(body("5cf92f3577b34da6a3ce929d0e0e4737"), MESSAGE_ID);

        assertThat(seen[0]).isEqualTo("5cf92f3577b34da6a3ce929d0e0e4737");
        assertThat(TraceIdContext.current()).isEqualTo(TRACE_ID);
    }

    @Test
    void anInvalidPayloadTraceIdNeverOverwritesTheAmbientOne() {
        TraceIdContext.set(TRACE_ID);

        ConsumeResult result = handler.handle(body("not-a-trace"), MESSAGE_ID);

        // 本用例钉的是 handler 不做二次校验：载荷 traceId 非法由落库侧拒绝，但线程上下文不能被脏值污染。
        assertThat(result).isEqualTo(ConsumeResult.ACK);
        assertThat(TraceIdContext.current()).isEqualTo(TRACE_ID);
    }

    private byte[] body(String traceId) {
        return ("{\"eventId\":\"0192f3a4-a1b2-7c33-9d01-1234567890ab\","
                + "\"traceId\":\"" + traceId + "\","
                + "\"reminderId\":\"0192f3a4-7b2c-7d10-9e8f-1234567890ac\","
                + "\"userId\":\"0192f3a4-7b2c-7d10-9e8f-1234567890ab\","
                + "\"title\":\"线性代数\","
                + "\"description\":\"第三章\","
                + "\"sourceType\":\"COURSE\","
                + "\"rrule\":\"FREQ=WEEKLY;BYDAY=MO\","
                + "\"timezone\":\"Asia/Shanghai\","
                + "\"occurrenceStartUtc\":\"2026-08-31T00:50:00Z\","
                + "\"nextTriggerAt\":\"2026-09-07T00:50:00Z\","
                + "\"occurredAt\":\"2026-08-31T00:50:03Z\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
