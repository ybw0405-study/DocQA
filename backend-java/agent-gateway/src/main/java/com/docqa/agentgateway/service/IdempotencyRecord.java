package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AskData;
import java.time.Instant;

public record IdempotencyRecord(
        IdempotencyStatus status,
        String fingerprint,
        AskData response,
        String errorCode,
        Instant updatedAt) {
}
