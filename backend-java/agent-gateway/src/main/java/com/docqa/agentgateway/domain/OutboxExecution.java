package com.docqa.agentgateway.domain;

import com.docqa.agentgateway.dto.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OutboxExecution(UUID id, Instant recordedAt, List<AuditEvent> events) {
}
