package com.docqa.agentgateway.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 模型连接的库内行，**含密文**，只在 repository ↔ service 之间流动。
 *
 * <p>刻意不放进 {@code dto} 包：controller 不得依赖 repository（ArchUnit），
 * 因此密文没有任何路径能直接进到 HTTP 响应里；对外形态是
 * {@code dto.ModelConnection}，只有 {@code hasCredential} 布尔。
 */
public record ModelConnectionRow(
        UUID id,
        UUID tenantId,
        String name,
        String presetId,
        String baseUrl,
        String model,
        boolean enabled,
        boolean active,
        byte[] credential,
        String credentialKeyId,
        String probeStatus,
        Instant lastProbedAt,
        Instant createdAt,
        Instant updatedAt,
        UUID updatedBy) {

    public boolean hasCredential() {
        return credential != null && credential.length > 0;
    }
}
