package com.docqa.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.audit.config.AuditProperties;
import com.docqa.audit.dto.AuditEventMessage;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventNormalizerTest {

    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-a1b2-7c33-9d01-1234567890ab");
    private static final UUID USER_ID = UUID.fromString("0192f3a4-7b2c-7d10-9e8f-1234567890ab");
    private static final UUID CONVERSATION_ID = UUID.fromString("0192f3a4-8c3d-7e21-8f90-abcdef123456");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String OTHER_TRACE_ID = "5cf92f3577b34da6a3ce929d0e0e4737";
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    private final AuditProperties properties = new AuditProperties();
    private final AuditEventNormalizer normalizer = new AuditEventNormalizer(new AuditPayloadSanitizer(), properties);

    @Test
    void prefersTheMessagePropertyTraceIdAndNormalizesCoreFields() {
        AuditEventMessage message = new AuditEventMessage(
                EVENT_ID,
                "RAG_RETRIEVED",
                "  rag_retriever  ",
                "python",
                "info",
                NOW.minusSeconds(1),
                OTHER_TRACE_ID,
                USER_ID,
                CONVERSATION_ID,
                Map.of("chunkCount", 6), null);

        AuditEventNormalizer.NormalizedAuditEvent result = normalizer.normalize(message, TRACE_ID, NOW);

        assertThat(result.event().id()).isEqualTo(EVENT_ID);
        assertThat(result.event().traceId()).isEqualTo(TRACE_ID);
        assertThat(result.event().userId()).isEqualTo(USER_ID);
        assertThat(result.event().eventType()).isEqualTo("RAG_RETRIEVED");
        assertThat(result.event().agentName()).isEqualTo("rag_retriever");
        assertThat(result.event().source()).isEqualTo("PYTHON");
        assertThat(result.event().severity()).isEqualTo("INFO");
        assertThat(result.event().payloadRedacted()).containsEntry("chunkCount", 6L);
        assertThat(result.event().occurredAt()).isEqualTo(NOW.minusSeconds(1));
        assertThat(result.event().ingestedAt()).isEqualTo(NOW);
        assertThat(result.warnings()).contains("severity");
    }

    @Test
    void fallsBackToThePayloadTraceIdWhenThePropertyIsMissingOrInvalid() {
        AuditEventMessage message = message(Map.of());

        assertThat(normalizer.normalize(message, null, NOW).event().traceId()).isEqualTo(OTHER_TRACE_ID);
        assertThat(normalizer.normalize(message, "not-a-trace", NOW).event().traceId())
                .isEqualTo(OTHER_TRACE_ID);
    }

    @Test
    void rejectsEventsWithoutAnAuthoritativeTraceId() {
        AuditEventMessage message = new AuditEventMessage(
                EVENT_ID, "RAG_RETRIEVED", "rag_retriever", "PYTHON", "INFO",
                NOW, "00000000000000000000000000000000", USER_ID, CONVERSATION_ID, Map.of(), null);

        assertThatThrownBy(() -> normalizer.normalize(message, null, NOW))
                .isInstanceOf(AuditEventRejectedException.class)
                .hasMessage("invalid traceId");
    }

    @Test
    void rejectsEventsWithoutRequiredCoreFields() {
        assertThatThrownBy(() -> normalizer.normalize(
                new AuditEventMessage(null, "RAG_RETRIEVED", "rag", "PYTHON", "INFO",
                        NOW, TRACE_ID, USER_ID, CONVERSATION_ID, Map.of(), null), null, NOW))
                .hasMessage("missing eventId");
        assertThatThrownBy(() -> normalizer.normalize(
                new AuditEventMessage(EVENT_ID, "RAG_RETRIEVED", "rag", "PYTHON", "INFO",
                        NOW, TRACE_ID, null, CONVERSATION_ID, Map.of(), null), null, NOW))
                .hasMessage("missing userId");
        assertThatThrownBy(() -> normalizer.normalize(
                new AuditEventMessage(EVENT_ID, "RAG_RETRIEVED", "rag", "PYTHON", "INFO",
                        null, TRACE_ID, USER_ID, CONVERSATION_ID, Map.of(), null), null, NOW))
                .hasMessage("missing occurredAt");
        assertThatThrownBy(() -> normalizer.normalize(null, TRACE_ID, NOW))
                .hasMessage("missing audit event");
    }

    @Test
    void rejectsInvalidEventTypeAndSource() {
        assertThatThrownBy(() -> normalizer.normalize(
                new AuditEventMessage(EVENT_ID, "rag retrieved", "rag", "PYTHON", "INFO",
                        NOW, TRACE_ID, USER_ID, CONVERSATION_ID, Map.of(), null), null, NOW))
                .hasMessage("invalid eventType");
        assertThatThrownBy(() -> normalizer.normalize(
                new AuditEventMessage(EVENT_ID, "RAG_RETRIEVED", "rag", "RUST", "INFO",
                        NOW, TRACE_ID, USER_ID, CONVERSATION_ID, Map.of(), null), null, NOW))
                .hasMessage("invalid source");
        assertThatThrownBy(() -> normalizer.normalize(
                new AuditEventMessage(EVENT_ID, "RAG_RETRIEVED", "rag", null, "INFO",
                        NOW, TRACE_ID, USER_ID, CONVERSATION_ID, Map.of(), null), null, NOW))
                .hasMessage("missing source");
    }

    @Test
    void defaultsMissingSeverityAndDropsOverlongAgentNames() {
        AuditEventMessage message = new AuditEventMessage(
                EVENT_ID, "RAG_RETRIEVED", "a".repeat(65), "PYTHON", "UNKNOWN",
                NOW, TRACE_ID, USER_ID, CONVERSATION_ID, Map.of(), null);

        AuditEventNormalizer.NormalizedAuditEvent result = normalizer.normalize(message, null, NOW);

        assertThat(result.event().severity()).isEqualTo("INFO");
        assertThat(result.event().agentName()).isNull();
        assertThat(result.warnings()).contains("agentName", "severity");
    }

    @Test
    void dropsPiiLikeAgentNames() {
        AuditEventMessage message = new AuditEventMessage(
                EVENT_ID, "RAG_RETRIEVED", "student@example.com", "PYTHON", "INFO",
                NOW, TRACE_ID, USER_ID, CONVERSATION_ID, Map.of(), null);

        AuditEventNormalizer.NormalizedAuditEvent result = normalizer.normalize(message, null, NOW);

        assertThat(result.event().agentName()).isNull();
        assertThat(result.warnings()).contains("agentName");
    }

    @Test
    void clampsOccurredAtFromTheFutureAndPreservesPastTimestamps() {
        AuditEventMessage future = new AuditEventMessage(
                EVENT_ID, "RAG_RETRIEVED", "rag", "PYTHON", "INFO",
                NOW.plusSeconds(3600), TRACE_ID, USER_ID, CONVERSATION_ID, Map.of(), null);
        AuditEventMessage past = new AuditEventMessage(
                EVENT_ID, "RAG_RETRIEVED", "rag", "PYTHON", "INFO",
                NOW.minusSeconds(86400), TRACE_ID, USER_ID, CONVERSATION_ID, Map.of(), null);

        AuditEventNormalizer.NormalizedAuditEvent clamped = normalizer.normalize(future, null, NOW);
        AuditEventNormalizer.NormalizedAuditEvent preserved = normalizer.normalize(past, null, NOW);

        assertThat(clamped.event().occurredAt()).isEqualTo(NOW);
        assertThat(clamped.warnings()).contains("occurredAt");
        assertThat(preserved.event().occurredAt()).isEqualTo(NOW.minusSeconds(86400));
        assertThat(preserved.warnings()).doesNotContain("occurredAt");
    }

    @Test
    void dropsNonWhitelistedPayloadFieldsDuringNormalization() {
        AuditEventMessage message = message(Map.of(
                "question", "完整问题原文",
                "chunkCount", 6));

        AuditEventNormalizer.NormalizedAuditEvent result = normalizer.normalize(message, null, NOW);

        assertThat(result.event().payloadRedacted()).containsExactlyEntriesOf(Map.of("chunkCount", 6L));
        assertThat(result.warnings()).contains("unknown");
    }

    private AuditEventMessage message(Map<String, Object> payload) {
        return new AuditEventMessage(
                EVENT_ID,
                "RAG_RETRIEVED",
                "rag_retriever",
                "PYTHON",
                "INFO",
                NOW.minusSeconds(1),
                OTHER_TRACE_ID,
                USER_ID,
                CONVERSATION_ID,
                payload, null);
    }
}
