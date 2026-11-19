package com.docqa.audit.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.docqa.audit.dto.AuditEventMessage;
import com.docqa.audit.service.AuditEventRejectedException;
import com.docqa.audit.service.AuditEventService;
import com.docqa.common.trace.TraceIdContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditMessageHandlerTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String OTHER_TRACE_ID = "5cf92f3577b34da6a3ce929d0e0e4737";

    @Mock
    private AuditEventService auditEventService;

    private AuditMessageHandler handler;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        handler = new AuditMessageHandler(objectMapper, auditEventService);
    }

    @AfterEach
    void clearTrace() {
        TraceIdContext.clear();
    }

    @Test
    void acksAValidEventAndPassesThePropertyTraceId() {
        ConsumeResult result = handler.handle(body(OTHER_TRACE_ID), TRACE_ID);

        assertThat(result).isEqualTo(ConsumeResult.ACK);
        verify(auditEventService).ingest(any(AuditEventMessage.class), eq(TRACE_ID));
    }

    @Test
    void ignoresUnknownTopLevelFields() {
        ConsumeResult result = handler.handle(bodyWithUnknownField(TRACE_ID), TRACE_ID);

        assertThat(result).isEqualTo(ConsumeResult.ACK);
        verify(auditEventService).ingest(any(AuditEventMessage.class), eq(TRACE_ID));
    }

    @Test
    void rejectsAnEmptyBodyAndLetsRocketMqDeadLetterIt() {
        ConsumeResult result = handler.handle(new byte[0], TRACE_ID);

        assertThat(result).isEqualTo(ConsumeResult.RETRY);
        verifyNoInteractions(auditEventService);
    }

    @Test
    void rejectsMalformedJsonWithoutCallingTheService() {
        ConsumeResult result = handler.handle("{not-json".getBytes(StandardCharsets.UTF_8), TRACE_ID);

        assertThat(result).isEqualTo(ConsumeResult.RETRY);
        verifyNoInteractions(auditEventService);
    }

    @Test
    void rejectsEventsWithInvalidCoreFieldsSoTheyReachTheDeadLetterQueue() {
        doThrow(new AuditEventRejectedException("invalid eventType"))
                .when(auditEventService).ingest(any(), eq(TRACE_ID));

        ConsumeResult result = handler.handle(body(TRACE_ID), TRACE_ID);

        assertThat(result).isEqualTo(ConsumeResult.RETRY);
    }

    @Test
    void requestsARetryWhenStorageFails() {
        doThrow(new IllegalStateException("database down"))
                .when(auditEventService).ingest(any(), eq(TRACE_ID));

        ConsumeResult result = handler.handle(body(TRACE_ID), TRACE_ID);

        assertThat(result).isEqualTo(ConsumeResult.RETRY);
    }

    @Test
    void exposesThePropertyTraceIdToTheServiceAndRestoresThePreviousContext() {
        String captured = captureTraceIdDuringIngest(TRACE_ID);

        assertThat(captured).isEqualTo(TRACE_ID);
        assertThat(TraceIdContext.current()).isNull();
    }

    @Test
    void fallsBackToThePayloadTraceIdWhenThePropertyIsInvalid() {
        String captured = captureTraceIdDuringIngest("not-a-trace");

        assertThat(captured).isEqualTo(TRACE_ID);
    }

    private String captureTraceIdDuringIngest(String traceIdProperty) {
        String[] captured = new String[1];
        org.mockito.Mockito.doAnswer(invocation -> {
            captured[0] = TraceIdContext.current();
            return AuditEventService.IngestOutcome.INGESTED;
        }).when(auditEventService).ingest(any(), eq(traceIdProperty));
        handler.handle(body(TRACE_ID), traceIdProperty);
        return captured[0];
    }

    @Test
    void carriesTenantIdFromTheWireBodyIntoTheMessage() {
        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        String json = new String(body(TRACE_ID), StandardCharsets.UTF_8);
        json = json.substring(0, json.length() - 1) + ",\"tenantId\":\"" + tenantId + "\"}";

        ConsumeResult result = handler.handle(json.getBytes(StandardCharsets.UTF_8), TRACE_ID);

        assertThat(result).isEqualTo(ConsumeResult.ACK);
        ArgumentCaptor<AuditEventMessage> captor = ArgumentCaptor.forClass(AuditEventMessage.class);
        verify(auditEventService).ingest(captor.capture(), eq(TRACE_ID));
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    private byte[] body(String payloadTraceId) {
        return ("{\"eventId\":\"0192f3a4-a1b2-7c33-9d01-1234567890ab\","
                + "\"eventType\":\"RAG_RETRIEVED\","
                + "\"agentName\":\"rag_retriever\","
                + "\"source\":\"PYTHON\","
                + "\"severity\":\"INFO\","
                + "\"occurredAt\":\"2026-10-20T10:15:01.120Z\","
                + "\"traceId\":\"" + payloadTraceId + "\","
                + "\"userId\":\"0192f3a4-7b2c-7d10-9e8f-1234567890ab\","
                + "\"conversationId\":\"0192f3a4-8c3d-7e21-8f90-abcdef123456\","
                + "\"payloadRedacted\":{\"chunkCount\":6}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] bodyWithUnknownField(String payloadTraceId) {
        String json = new String(body(payloadTraceId), StandardCharsets.UTF_8);
        return (json.substring(0, json.length() - 1) + ",\"forgedUserId\":\""
                + UUID.randomUUID() + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
