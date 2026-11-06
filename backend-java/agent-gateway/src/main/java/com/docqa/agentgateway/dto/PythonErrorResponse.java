package com.docqa.agentgateway.dto;

import java.util.List;

public record PythonErrorResponse(String code, String detail, List<PythonAuditEvent> auditEvents) {
}
