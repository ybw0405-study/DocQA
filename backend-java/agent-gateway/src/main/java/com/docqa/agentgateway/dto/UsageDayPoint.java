package com.docqa.agentgateway.dto;

/** 按 UTC 自然日聚合的一个数据点，`date` 形如 `2026-11-03`。 */
public record UsageDayPoint(String date, long questions, long tokens, long failed) {
}
