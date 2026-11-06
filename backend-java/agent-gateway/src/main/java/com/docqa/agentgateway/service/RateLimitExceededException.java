package com.docqa.agentgateway.service;

public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;
    private final String detail;

    public RateLimitExceededException(long retryAfterSeconds) {
        this(retryAfterSeconds, null);
    }

    protected RateLimitExceededException(long retryAfterSeconds, String detail) {
        super("Ask rate limit exceeded");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        this.detail = detail;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 为 null 时由 ProblemDetailFactory 回落到错误码默认文案。 */
    public String getDetail() {
        return detail;
    }
}
