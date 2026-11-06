package com.docqa.document.service;

import java.time.Instant;

public record IdempotencyRecord(String fingerprint, String responseJson, Instant createdAt) {
}
