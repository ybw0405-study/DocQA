package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.agentgateway.dto.AuditEvent;
import com.docqa.agentgateway.dto.PythonAuditEvent;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditEventValidatorTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private AuditEventValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AuditEventValidator(new AuditEventFactory(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void acceptsValidEventsAndOverridesAuthorityFields() {
        PythonAuditEvent pythonEvent = event(Map.of("chunkCount", 4));

        List<AuditEvent> events = validator.validateResponse(
                List.of(pythonEvent), TRACE_ID, USER_ID, CONVERSATION_ID);

        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.eventId()).isEqualTo(pythonEvent.eventId());
        assertThat(event.source()).isEqualTo("PYTHON");
        assertThat(event.traceId()).isEqualTo(TRACE_ID);
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(event.payloadRedacted()).containsEntry("chunkCount", 4);
    }

    @Test
    void rejectsEmptyEventList() {
        assertViolation(List.of());
    }

    @Test
    void rejectsMoreThanFiftyEvents() {
        List<PythonAuditEvent> events = new ArrayList<>();
        for (int index = 0; index < AuditEventValidator.MAX_EVENTS + 1; index++) {
            events.add(event(Map.of()));
        }

        assertViolation(events);
    }

    @Test
    void rejectsDuplicateEventIds() {
        PythonAuditEvent event = event(Map.of());

        assertViolation(List.of(event, event));
    }

    @Test
    void rejectsInvalidSeverity() {
        PythonAuditEvent event = new PythonAuditEvent(
                UUID.randomUUID(), "RAG_RETRIEVED", "rag", "TRACE", NOW, Map.of());

        assertViolation(List.of(event));
    }

    @Test
    void rejectsInvalidEventType() {
        PythonAuditEvent event = new PythonAuditEvent(
                UUID.randomUUID(), "rag-retrieved", "rag", "INFO", NOW, Map.of());

        assertViolation(List.of(event));
    }

    @Test
    void dropsNonWhitelistedPayloadFieldsAndAddsMarker() {
        List<AuditEvent> events = validator.validateResponse(
                List.of(event(Map.of("question", "完整问题", "tokenCount", 5))),
                TRACE_ID, USER_ID, CONVERSATION_ID);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).payloadRedacted()).containsOnlyKeys("tokenCount");
        assertThat(events.get(1).eventType()).isEqualTo("AUDIT_PAYLOAD_REJECTED");
    }

    @Test
    void truncatesOverlongPayloadStrings() {
        List<AuditEvent> events = validator.validateResponse(
                List.of(event(Map.of("model", "m".repeat(300)))),
                TRACE_ID, USER_ID, CONVERSATION_ID);

        assertThat(events.get(0).payloadRedacted().get("model").toString()).hasSize(256);
    }

    @Test
    void keepsToolSourceAndTargetUserIdAfterFiltering() {
        List<AuditEvent> events = validator.validateResponse(
                List.of(event(Map.of(
                        "toolName", "mcp__dict__lookup",
                        "toolSource", "mcp",
                        "targetUserId", "00000000-0000-0000-0000-000000000001"))),
                TRACE_ID, USER_ID, CONVERSATION_ID);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).payloadRedacted())
                .containsEntry("toolSource", "mcp")
                .containsEntry("targetUserId", "00000000-0000-0000-0000-000000000001");
    }

    @Test
    void errorValidationReturnsEmptyForNoEvents() {
        AuditValidation validation = validator.validateError(
                List.of(), TRACE_ID, USER_ID, CONVERSATION_ID);

        assertThat(validation.events()).isEmpty();
        assertThat(validation.gap()).isFalse();
    }

    @Test
    void errorValidationMarksGapForTooManyEvents() {
        List<PythonAuditEvent> events = new ArrayList<>();
        for (int index = 0; index < AuditEventValidator.MAX_EVENTS + 1; index++) {
            events.add(event(Map.of()));
        }

        AuditValidation validation = validator.validateError(
                events, TRACE_ID, USER_ID, CONVERSATION_ID);

        assertThat(validation.events()).hasSize(AuditEventValidator.MAX_EVENTS);
        assertThat(validation.gap()).isTrue();
    }

    @Test
    void errorValidationSkipsMalformedEventAndMarksGap() {
        PythonAuditEvent malformed = new PythonAuditEvent(
                null, "RAG_RETRIEVED", "rag", "INFO", NOW, Map.of());

        AuditValidation validation = validator.validateError(
                List.of(malformed, event(Map.of())), TRACE_ID, USER_ID, CONVERSATION_ID);

        assertThat(validation.events()).hasSize(1);
        assertThat(validation.gap()).isTrue();
    }

    private PythonAuditEvent event(Map<String, Object> payload) {
        return new PythonAuditEvent(
                UUID.randomUUID(), "RAG_RETRIEVED", "rag", "INFO", NOW, payload);
    }

    private void assertViolation(List<PythonAuditEvent> events) {
        assertThatThrownBy(() -> validator.validateResponse(
                events, TRACE_ID, USER_ID, CONVERSATION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_004);
    }
}
