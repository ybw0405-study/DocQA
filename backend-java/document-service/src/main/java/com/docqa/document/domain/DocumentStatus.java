package com.docqa.document.domain;

public enum DocumentStatus {
    UPLOADED,
    PARSING,
    INDEXED,
    FAILED,
    DEAD;

    public static DocumentStatus from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("document status is missing");
        }
        return valueOf(value);
    }
}
