package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.AgentProperties;
import com.docqa.agentgateway.dto.AuthorizedDocument;
import com.docqa.agentgateway.dto.Citation;
import com.docqa.agentgateway.dto.PythonAskResponse;
import com.docqa.agentgateway.dto.PythonPendingApproval;
import com.docqa.agentgateway.dto.PythonCitation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PythonResponseValidator {

    private static final String PROTOCOL_VERSION = "1.0";
    private static final int MAX_ANSWER_LENGTH = 8000;
    private static final int MAX_SNIPPET_LENGTH = 200;
    private static final Set<String> BUDGET_STATUSES =
            Set.of("OK", "LOW_CONFIDENCE", "BUDGET_EXHAUSTED", "PENDING_APPROVAL");

    private final AgentProperties properties;
    private final AuditEventValidator auditEventValidator;

    public PythonResponseValidator(AgentProperties properties, AuditEventValidator auditEventValidator) {
        this.properties = properties;
        this.auditEventValidator = auditEventValidator;
    }

    public ValidatedAnswer validate(
            PythonAskResponse response,
            String traceId,
            UUID userId,
            UUID conversationId,
            List<AuthorizedDocument> documents,
            BudgetSnapshot budget) {
        if (response == null) {
            throw violation("response is missing");
        }
        if (!PROTOCOL_VERSION.equals(response.protocolVersion())) {
            throw violation("protocolVersion is not supported");
        }
        if (!traceId.equals(response.traceId())) {
            throw violation("traceId does not match");
        }
        if (response.answer() == null || response.answer().isBlank()) {
            throw violation("answer is empty");
        }
        if (response.answer().length() > MAX_ANSWER_LENGTH) {
            throw violation("answer exceeds the maximum length");
        }
        if (!Double.isFinite(response.confidence()) || response.confidence() < 0.0 || response.confidence() > 1.0) {
            throw violation("confidence is outside 0..1");
        }
        if (response.finishedAt() == null) {
            throw violation("finishedAt is missing");
        }
        if (response.budgetStatus() == null || !BUDGET_STATUSES.contains(response.budgetStatus())) {
            throw violation("budgetStatus is invalid");
        }
        boolean pending = "PENDING_APPROVAL".equals(response.budgetStatus());
        if (pending != (response.pendingApproval() != null)) {
            throw violation("pendingApproval must accompany PENDING_APPROVAL exactly");
        }
        if (pending) {
            PythonPendingApproval pa = response.pendingApproval();
            if (pa.tool() == null || pa.tool().isBlank() || pa.tool().length() > 200
                    || pa.input() == null || pa.input().length() > 2000) {
                throw violation("pendingApproval payload is invalid");
            }
        }
        if (response.tokenUsed() < 0) {
            throw violation("tokenUsed is negative");
        }
        if (response.toolCallsMade() < 0 || response.roundsUsed() < 0) {
            throw violation("agent usage counters are negative");
        }

        List<Citation> citations = validateCitations(response.citations(), documents);
        validateBudgetSnapshot(response, budget);

        List<com.docqa.agentgateway.dto.AuditEvent> auditEvents = auditEventValidator.validateResponse(
                response.auditEvents(),
                traceId,
                userId,
                conversationId);

        String effectiveBudgetStatus = effectiveBudgetStatus(response, citations);
        return new ValidatedAnswer(citations, auditEvents, effectiveBudgetStatus);
    }

    private void validateBudgetSnapshot(PythonAskResponse response, BudgetSnapshot budget) {
        if (budget == null) {
            throw violation("budget snapshot is missing");
        }
        if (response.tokenUsed() > budget.tokenBudget()) {
            throw violation("tokenUsed exceeds the token budget");
        }
        if (response.toolCallsMade() > budget.maxToolCalls() || response.roundsUsed() > budget.maxRounds()) {
            throw violation("agent usage exceeds the configured budget");
        }
        if (response.tokenUsed() > budget.tokensUsed()
                || response.toolCallsMade() > budget.toolCallsMade()
                || response.roundsUsed() > budget.roundsUsed()) {
            throw violation("Python budget counters are behind the reported usage");
        }
    }

    private List<Citation> validateCitations(List<PythonCitation> citations, List<AuthorizedDocument> documents) {
        if (citations == null || citations.size() > properties.getMaxCitations()) {
            throw violation("citations exceed the configured limit");
        }
        Set<UUID> allowedDocuments = documents.stream()
                .map(AuthorizedDocument::documentId)
                .collect(Collectors.toSet());
        return citations.stream().map(citation -> {
            if (citation == null
                    || citation.documentId() == null
                    || !allowedDocuments.contains(citation.documentId())) {
                throw violation("citation references a document outside the authorized scope");
            }
            if (citation.chunkIndex() < 0) {
                throw violation("citation chunkIndex is negative");
            }
            if (!Double.isFinite(citation.score()) || citation.score() < 0.0 || citation.score() > 1.0) {
                throw violation("citation score is outside 0..1");
            }
            if (citation.snippet() == null || citation.snippet().length() > MAX_SNIPPET_LENGTH) {
                throw violation("citation snippet is missing or too long");
            }
            return new Citation(
                    citation.documentId(),
                    citation.chunkIndex(),
                    citation.score(),
                    citation.snippet());
        }).toList();
    }

    private String effectiveBudgetStatus(PythonAskResponse response, List<Citation> citations) {
        if ("PENDING_APPROVAL".equals(response.budgetStatus())) {
            return "PENDING_APPROVAL";
        }
        if ("BUDGET_EXHAUSTED".equals(response.budgetStatus())) {
            return "BUDGET_EXHAUSTED";
        }
        if ("LOW_CONFIDENCE".equals(response.budgetStatus())
                || response.confidence() < properties.getLowConfidenceThreshold()
                || citations.isEmpty()) {
            return "LOW_CONFIDENCE";
        }
        return "OK";
    }

    private BusinessException violation(String detail) {
        return new BusinessException(ErrorCode.AGT_UPSTREAM_004, detail);
    }
}
