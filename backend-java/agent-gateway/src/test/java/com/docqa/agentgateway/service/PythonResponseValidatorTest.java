package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.agentgateway.config.AgentProperties;
import com.docqa.agentgateway.dto.AuditEvent;
import com.docqa.agentgateway.dto.AuthorizedDocument;
import com.docqa.agentgateway.dto.PythonAskResponse;
import com.docqa.agentgateway.dto.PythonAuditEvent;
import com.docqa.agentgateway.dto.PythonCitation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PythonResponseValidatorTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private PythonResponseValidator validator;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        AuditEventFactory factory = new AuditEventFactory(Clock.fixed(NOW, ZoneOffset.UTC));
        validator = new PythonResponseValidator(properties, new AuditEventValidator(factory));
    }

    @Test
    void acceptsGroundedAnswer() {
        ValidatedAnswer answer = validator.validate(
                response(List.of(citation(DOCUMENT_ID, 0, 0.9, "片段")), 0.9, "OK", 120, 1, 2),
                TRACE_ID, USER_ID, CONVERSATION_ID,
                List.of(new AuthorizedDocument(DOCUMENT_ID, "v1")),
                budget(120, 1, 2));

        assertThat(answer.budgetStatus()).isEqualTo("OK");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.auditEvents()).hasSize(1);
    }

    @Test
    void downgradesAnswerWithoutCitationsToLowConfidence() {
        ValidatedAnswer answer = validator.validate(
                response(List.of(), 0.9, "OK", 10, 0, 1),
                TRACE_ID, USER_ID, CONVERSATION_ID,
                List.of(new AuthorizedDocument(DOCUMENT_ID, "v1")),
                budget(10, 0, 1));

        assertThat(answer.budgetStatus()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    void downgradesAnswerBelowConfidenceThreshold() {
        ValidatedAnswer answer = validator.validate(
                response(List.of(citation(DOCUMENT_ID, 0, 0.5, "片段")), 0.5, "OK", 10, 0, 1),
                TRACE_ID, USER_ID, CONVERSATION_ID,
                List.of(new AuthorizedDocument(DOCUMENT_ID, "v1")),
                budget(10, 0, 1));

        assertThat(answer.budgetStatus()).isEqualTo("LOW_CONFIDENCE");
    }

    @Test
    void budgetExhaustedTakesPrecedence() {
        ValidatedAnswer answer = validator.validate(
                response(List.of(), 0.1, "BUDGET_EXHAUSTED", 10, 0, 1),
                TRACE_ID, USER_ID, CONVERSATION_ID,
                List.of(new AuthorizedDocument(DOCUMENT_ID, "v1")),
                budget(10, 0, 1));

        assertThat(answer.budgetStatus()).isEqualTo("BUDGET_EXHAUSTED");
    }

    @Test
    void rejectsTraceMismatch() {
        PythonAskResponse response = response(
                List.of(citation(DOCUMENT_ID, 0, 0.9, "片段")), 0.9, "OK", 10, 0, 1,
                "00000000000000000000000000000000");

        assertViolation(response, budget(10, 0, 1));
    }

    @Test
    void rejectsUnsupportedProtocolVersion() {
        PythonAskResponse response = new PythonAskResponse(
                "2.0", TRACE_ID, "答案", List.of(citation(DOCUMENT_ID, 0, 0.9, "片段")),
                0.9, "OK", 10, 0, 1, auditEvents(), NOW);

        assertViolation(response, budget(10, 0, 1));
    }

    @Test
    void rejectsBlankAnswer() {
        PythonAskResponse response = new PythonAskResponse(
                "1.0", TRACE_ID, " ", List.of(), 0.9, "OK", 10, 0, 1, auditEvents(), NOW);

        assertViolation(response, budget(10, 0, 1));
    }

    @Test
    void rejectsConfidenceOutsideRange() {
        assertViolation(response(List.of(), 1.5, "OK", 10, 0, 1), budget(10, 0, 1));
    }

    @Test
    void rejectsMissingFinishedAt() {
        PythonAskResponse response = new PythonAskResponse(
                "1.0", TRACE_ID, "答案", List.of(), 0.9, "OK", 10, 0, 1, auditEvents(), null);

        assertViolation(response, budget(10, 0, 1));
    }

    @Test
    void rejectsUnknownBudgetStatus() {
        assertViolation(response(List.of(), 0.9, "UNKNOWN", 10, 0, 1), budget(10, 0, 1));
    }

    @Test
    void rejectsCitationOutsideAuthorizedScope() {
        UUID foreign = UUID.randomUUID();
        assertViolation(
                response(List.of(citation(foreign, 0, 0.9, "片段")), 0.9, "OK", 10, 0, 1),
                budget(10, 0, 1));
    }

    @Test
    void rejectsOverlongCitationSnippet() {
        assertViolation(
                response(List.of(citation(DOCUMENT_ID, 0, 0.9, "a".repeat(201))), 0.9, "OK", 10, 0, 1),
                budget(10, 0, 1));
    }

    @Test
    void rejectsUsageAboveBudget() {
        assertViolation(
                response(List.of(citation(DOCUMENT_ID, 0, 0.9, "片段")), 0.9, "OK", 9000, 0, 1),
                budget(100, 0, 1));
    }

    @Test
    void rejectsUsageAheadOfBudgetCounters() {
        assertViolation(
                response(List.of(citation(DOCUMENT_ID, 0, 0.9, "片段")), 0.9, "OK", 50, 0, 1),
                budget(10, 0, 1));
    }

    @Test
    void rejectsMissingBudgetSnapshot() {
        assertViolation(
                response(List.of(citation(DOCUMENT_ID, 0, 0.9, "片段")), 0.9, "OK", 10, 0, 1),
                null);
    }

    @Test
    void rejectsMissingAuditEvents() {
        PythonAskResponse response = new PythonAskResponse(
                "1.0", TRACE_ID, "答案", List.of(citation(DOCUMENT_ID, 0, 0.9, "片段")),
                0.9, "OK", 10, 0, 1, List.of(), NOW);

        assertViolation(response, budget(10, 0, 1));
    }

    @Test
    void replacesRejectedAuditPayloadWithMarker() {
        PythonAskResponse response = new PythonAskResponse(
                "1.0", TRACE_ID, "答案", List.of(citation(DOCUMENT_ID, 0, 0.9, "片段")),
                0.9, "OK", 10, 0, 1,
                List.of(new PythonAuditEvent(
                        UUID.randomUUID(), "RAG_RETRIEVED", "rag", "INFO", NOW,
                        Map.of("question", "完整问题"))),
                NOW);

        ValidatedAnswer answer = validator.validate(
                response, TRACE_ID, USER_ID, CONVERSATION_ID,
                List.of(new AuthorizedDocument(DOCUMENT_ID, "v1")),
                budget(10, 0, 1));

        assertThat(answer.auditEvents())
                .extracting(AuditEvent::eventType)
                .containsExactly("RAG_RETRIEVED", "AUDIT_PAYLOAD_REJECTED");
        assertThat(answer.auditEvents().get(0).payloadRedacted()).isEmpty();
    }

    private void assertViolation(PythonAskResponse response, BudgetSnapshot budget) {
        assertThatThrownBy(() -> validator.validate(
                response, TRACE_ID, USER_ID, CONVERSATION_ID,
                List.of(new AuthorizedDocument(DOCUMENT_ID, "v1")), budget))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_004);
    }

    private PythonAskResponse response(
            List<PythonCitation> citations,
            double confidence,
            String budgetStatus,
            int tokenUsed,
            int toolCalls,
            int rounds) {
        return response(citations, confidence, budgetStatus, tokenUsed, toolCalls, rounds, TRACE_ID);
    }

    private PythonAskResponse response(
            List<PythonCitation> citations,
            double confidence,
            String budgetStatus,
            int tokenUsed,
            int toolCalls,
            int rounds,
            String traceId) {
        return new PythonAskResponse(
                "1.0", traceId, "答案", citations, confidence, budgetStatus,
                tokenUsed, toolCalls, rounds, auditEvents(), NOW);
    }

    private List<PythonAuditEvent> auditEvents() {
        return List.of(new PythonAuditEvent(
                UUID.randomUUID(), "RAG_RETRIEVED", "rag", "INFO", NOW, Map.of("chunkCount", 3)));
    }

    private PythonCitation citation(UUID documentId, int chunkIndex, double score, String snippet) {
        return new PythonCitation(documentId, chunkIndex, score, snippet);
    }

    private BudgetSnapshot budget(int tokensUsed, int toolCalls, int rounds) {
        return new BudgetSnapshot(tokensUsed, toolCalls, rounds, 8000, 3, 3, NOW.plusSeconds(15));
    }
}
