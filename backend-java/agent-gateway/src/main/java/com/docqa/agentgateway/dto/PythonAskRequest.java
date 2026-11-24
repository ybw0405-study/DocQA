package com.docqa.agentgateway.dto;

import java.util.List;
import java.util.UUID;

public record PythonAskRequest(
        String protocolVersion,
        UUID tenantId,
        UUID userId,
        String traceId,
        UUID conversationId,
        String question,
        String piiMaskedQuestion,
        String piiMaskingVersion,
        int tokenBudget,
        int maxToolCalls,
        int maxRounds,
        List<String> allowedTools,
        int deadlineMs,
        List<PythonDocumentScope> documentScopes,
        List<PythonHistoryMessage> history,
        Boolean resumeApproved) {
}
