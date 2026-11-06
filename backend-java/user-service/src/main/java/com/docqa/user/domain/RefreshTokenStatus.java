package com.docqa.user.domain;

public enum RefreshTokenStatus {
    ACTIVE,
    USED,
    REVOKED;

    public static RefreshTokenStatus from(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Unknown refresh token status: " + value, ex);
        }
    }
}
