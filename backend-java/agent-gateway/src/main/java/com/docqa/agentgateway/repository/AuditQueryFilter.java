package com.docqa.agentgateway.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计查询的过滤条件；`from` / `to` 同时为 null 表示只按过滤器查询、不限定时间窗。
 */
public record AuditQueryFilter(String traceId, UUID userId, String eventType, Instant from, Instant to) {
}
