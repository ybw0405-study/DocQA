package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AiQuota;
import com.docqa.agentgateway.repository.AiQuotaRepository;
import com.docqa.common.trace.TraceIdContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class QuotaGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuotaGuard.class);

    private final AiQuotaRepository quotaRepository;
    private final QuotaUsageStore usageStore;
    private final Clock clock;

    public QuotaGuard(AiQuotaRepository quotaRepository, QuotaUsageStore usageStore, Clock clock) {
        this.quotaRepository = quotaRepository;
        this.usageStore = usageStore;
        this.clock = clock;
    }

    /**
     * 问答准入闸：按主键读当日覆盖，再原子检查并计 1 次问答。缺行表示不设上限，
     * 但仍累计当日用量，这样管理员当天中途设置配额时能看到之前已用的次数。
     */
    public void reserve(UUID userId) {
        AiQuota quota = quotaRepository.findByUserId(userId).orElse(null);
        boolean allowed = usageStore.tryConsume(
                userId,
                currentDay(),
                quota == null ? null : quota.dailyQuestionLimit(),
                quota == null ? null : quota.dailyTokenLimit());
        if (!allowed) {
            throw new QuotaExceededException(secondsUntilNextUtcMidnight());
        }
    }

    /**
     * token 是软闸：拿到真实 usage 后才累加。此时回答已经生成，计量写失败不能反过来吞掉回答，
     * 只记录告警（与缓存写失败同一姿态）。
     */
    public void recordTokens(UUID userId, long tokens) {
        if (tokens <= 0) {
            return;
        }
        try {
            usageStore.addTokens(userId, currentDay(), tokens);
        } catch (RuntimeException ex) {
            LOGGER.warn(
                    "Unable to record quota token usage: traceId={}, tokens={}",
                    TraceIdContext.current(),
                    tokens);
        }
    }

    private LocalDate currentDay() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private long secondsUntilNextUtcMidnight() {
        Instant now = clock.instant();
        Instant nextMidnight = now.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        return Math.max(1, Duration.between(now, nextMidnight).getSeconds());
    }
}
