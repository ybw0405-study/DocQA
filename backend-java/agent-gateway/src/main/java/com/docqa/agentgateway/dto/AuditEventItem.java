package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 管理面审计查询返回的一条事件。
 *
 * <p>`payload` 只来自落库的 `payload_redacted`，写入时已过白名单与 PII 清洗；
 * 本对象不提供任何可变标记，审计表在应用层只允许追加。</p>
 */
public record AuditEventItem(
        UUID eventId,
        String traceId,
        UUID userId,
        String eventType,
        String agentName,
        String source,
        String severity,
        Map<String, Object> payload,
        Instant occurredAt) {
}
