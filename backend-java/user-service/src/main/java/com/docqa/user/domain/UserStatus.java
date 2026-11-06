package com.docqa.user.domain;

public enum UserStatus {
    ACTIVE,
    DISABLED;

    public static UserStatus from(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Unknown user status: " + value, ex);
        }
    }
}
