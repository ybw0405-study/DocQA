package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.ConversationData;
import java.time.Instant;

public record ConversationCreationRecord(
        IdempotencyStatus status,
        String fingerprint,
        ConversationData response,
        Instant updatedAt) {
}
