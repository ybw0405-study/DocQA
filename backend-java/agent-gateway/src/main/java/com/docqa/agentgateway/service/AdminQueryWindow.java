package com.docqa.agentgateway.service;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 管理面查询的 UTC 时间窗。
 *
 * <p>`from` / `to` 任一显式给出时都按显式窗口校验；都缺省时由调用方决定是否套用
 * 「最近 24 小时」的默认窗口：审计查询在已有 traceId / userId / eventType 过滤时
 * 不套用默认窗口（一次问答的审计时间线可能跨越 24 小时），用量汇总始终套用。</p>
 */
record AdminQueryWindow(Instant from, Instant to) {

    static final Duration DEFAULT_SPAN = Duration.ofHours(24);
    static final Duration MAX_SPAN = Duration.ofDays(31);

    static AdminQueryWindow resolve(String fromParam, String toParam, Clock clock, boolean defaultWhenOpen) {
        boolean hasFrom = fromParam != null && !fromParam.isBlank();
        boolean hasTo = toParam != null && !toParam.isBlank();
        if (!hasFrom && !hasTo && !defaultWhenOpen) {
            return new AdminQueryWindow(null, null);
        }
        Instant to = hasTo ? parse(toParam, "to") : clock.instant();
        Instant from = hasFrom ? parse(fromParam, "from") : to.minus(DEFAULT_SPAN);
        if (!from.isBefore(to)) {
            throw invalid("from", "must be earlier than to");
        }
        if (Duration.between(from, to).compareTo(MAX_SPAN) > 0) {
            throw invalid("from", "time window must not exceed 31 days");
        }
        return new AdminQueryWindow(from, to);
    }

    private static Instant parse(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalid(field, "must be an RFC 3339 date-time");
        }
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(
                ErrorCode.COM_VALIDATION_001,
                "invalid time window",
                List.of(FieldViolation.of(field, message)));
    }
}
