package com.docqa.document.dto;

import java.time.Instant;

public record PresignedPart(int partNumber, String url, Instant expiresAt) {
}
