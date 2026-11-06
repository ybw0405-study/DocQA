package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 管理面用量视图：只提供计数与均值，不提供分位数。
 * 延迟数字只代表这段时间窗内的观测均值，性能结论以压测为准。
 */
public record UsageSummary(
        Instant from,
        Instant to,
        String bucket,
        UsageTotals totals,
        List<UsageDayPoint> byDay,
        List<UsageStatusCount> byStatus,
        List<UsageTopUser> topUsers) {
}
