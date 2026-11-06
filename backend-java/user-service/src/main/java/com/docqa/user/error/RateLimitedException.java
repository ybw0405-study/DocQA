package com.docqa.user.error;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;

/**
 * 限流拒绝。错误码由调用点给出：登录与改密共用 USR-RATE_LIMIT-001（同一把限流器），
 * 注册用 COM-RATE_LIMIT-001（来源 IP 的通用写入口限流）。
 */
public class RateLimitedException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitedException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
