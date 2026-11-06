package com.docqa.reminder.service;

import java.time.Instant;

public record IdempotencyRecord(
        IdempotencyStatus status,
        String fingerprint,
        String responseJson,
        Instant updatedAt) {
}
