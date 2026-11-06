package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.List;

public record PythonAskResponse(
        String protocolVersion,
        String traceId,
        String answer,
        List<PythonCitation> citations,
        double confidence,
        String budgetStatus,
        int tokenUsed,
        int toolCallsMade,
        int roundsUsed,
        List<PythonAuditEvent> auditEvents,
        Instant finishedAt) {
}
