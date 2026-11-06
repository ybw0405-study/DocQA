package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AuditEvent;
import com.docqa.agentgateway.dto.Citation;
import java.util.List;

public record ValidatedAnswer(
        List<Citation> citations,
        List<AuditEvent> auditEvents,
        String budgetStatus) {
}
