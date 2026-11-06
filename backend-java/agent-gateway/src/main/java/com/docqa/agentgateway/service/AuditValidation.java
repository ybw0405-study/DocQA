package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AuditEvent;
import java.util.List;

public record AuditValidation(List<AuditEvent> events, boolean gap) {
}
