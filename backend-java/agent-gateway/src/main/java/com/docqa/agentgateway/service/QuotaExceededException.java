package com.docqa.agentgateway.service;

/** 日配额用尽复用 COM-RATE_LIMIT-001（429），但 detail 必须说明是配额而非突发限流。 */
public class QuotaExceededException extends RateLimitExceededException {

    public static final String DETAIL = "当日 AI 配额已用尽";

    public QuotaExceededException(long retryAfterSeconds) {
        super(retryAfterSeconds, DETAIL);
    }
}
