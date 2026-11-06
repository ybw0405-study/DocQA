package com.docqa.agentgateway.dto;

/**
 * 时间窗内的计数与均值。
 *
 * <p>口径：`questions` 含缓存命中；`tokens` = `SUM(token_used)`；
 * `failed` 只统计 `status='FAILED'`，预算兜底单独计入 `budgetExhausted`。
 * 窗口内没有执行时 `avgLatencyMs` 为 null。</p>
 */
public record UsageTotals(
        long questions,
        long success,
        long budgetExhausted,
        long failed,
        long tokens,
        long toolCalls,
        long rounds,
        long cacheHits,
        Double avgLatencyMs) {
}
