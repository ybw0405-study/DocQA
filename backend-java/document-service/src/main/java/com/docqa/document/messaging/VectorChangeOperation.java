package com.docqa.document.messaging;

public enum VectorChangeOperation {
    DELETE_DOCUMENT,
    DELETE_VERSION;

    public static VectorChangeOperation from(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
