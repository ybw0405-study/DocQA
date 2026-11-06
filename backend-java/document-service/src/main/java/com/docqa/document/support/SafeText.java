package com.docqa.document.support;

public final class SafeText {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    private SafeText() {
    }

    public static String errorMessage(String value, String fallback) {
        String normalized = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").trim();
        if (normalized.isEmpty()) {
            normalized = fallback;
        }
        if (normalized.length() > MAX_ERROR_MESSAGE_LENGTH) {
            normalized = normalized.substring(0, MAX_ERROR_MESSAGE_LENGTH);
        }
        return normalized;
    }
}
