package com.docqa.common.api;

public record FieldViolation(String field, String message) {

    private static final int MAX_FIELD_LENGTH = 200;
    private static final int MAX_MESSAGE_LENGTH = 300;

    public FieldViolation {
        field = normalize(field, "request", MAX_FIELD_LENGTH);
        message = normalize(message, "invalid value", MAX_MESSAGE_LENGTH);
    }

    public static FieldViolation of(String field, String message) {
        return new FieldViolation(field, message);
    }

    private static String normalize(String value, String fallback, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return fallback;
        }
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
