package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 模型连接的对外形态。**没有凭据字段**：只有 {@code hasCredential} 布尔，
 * 密文与明文 Key 都不出现在任何读接口里（与 AgentFlow 连接器页同一边界）。
 */
public record ModelConnection(
        UUID id,
        String name,
        String presetId,
        String baseUrl,
        String model,
        boolean enabled,
        boolean active,
        boolean hasCredential,
        String probeStatus,
        Instant lastProbedAt,
        Instant createdAt,
        Instant updatedAt,
        UUID updatedBy) {
}
