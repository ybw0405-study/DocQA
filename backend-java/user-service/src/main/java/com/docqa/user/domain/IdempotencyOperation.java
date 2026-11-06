package com.docqa.user.domain;

public enum IdempotencyOperation {
    REGISTER,
    CHANGE_PASSWORD,
    USER_ROLE_UPDATE,
    USER_STATUS_UPDATE
}
