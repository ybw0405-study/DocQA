package com.docqa.agentgateway.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 模型连接的持久化端口。除 {@link #findAllActive()} 外每个方法都必须带 tenantId：
 * MySQL 8 没有 RLS，租户边界完全靠 SQL 谓词（见 TenantIsolationContractTest）。
 */
public interface ModelConnectionRepository {

    List<ModelConnectionRow> findAll(UUID tenantId);

    Optional<ModelConnectionRow> findById(UUID tenantId, UUID connectionId);

    Optional<ModelConnectionRow> findByName(UUID tenantId, String name);

    Optional<ModelConnectionRow> findActive(UUID tenantId);

    int count(UUID tenantId);

    /**
     * 按 (tenant_id, name) upsert。**不触碰 is_active**：同名重复提交是「改配置」，
     * 不该顺带把一条备用连接提升成生效连接，也不该把生效连接降下来。
     */
    void upsert(UUID tenantId, UUID connectionId, String name, String presetId, String baseUrl, String model,
            boolean enabled, UUID updatedBy, Instant now);

    void updateCredential(UUID tenantId, UUID connectionId, byte[] credential, String credentialKeyId,
            UUID updatedBy, Instant now);

    /** 清空租户内全部生效标记；与 markActive 在同一事务里调用，唯一键全程只允许一条生效。 */
    void clearActive(UUID tenantId, UUID updatedBy, Instant now);

    /** 只取消这一条的生效标记；停用它不得影响租户内其它连接。 */
    void deactivate(UUID tenantId, UUID connectionId, UUID updatedBy, Instant now);

    void markActive(UUID tenantId, UUID connectionId, UUID updatedBy, Instant now);

    void updateProbeStatus(UUID tenantId, UUID connectionId, String probeStatus, Instant probedAt, Instant now);

    void delete(UUID tenantId, UUID connectionId);

    /**
     * 启动期全量重发布用的跨租户扫描：网关重启后要把每个租户的生效连接重新写进 Redis，
     * 因此必须一次取回所有租户的生效行。行自带 tenant_id，发布时逐行按租户建键，
     * 与审计 outbox relay 的跨租户扫描同一性质（写侧仍逐行带租户谓词）。
     */
    List<ModelConnectionRow> findAllActive();
}
