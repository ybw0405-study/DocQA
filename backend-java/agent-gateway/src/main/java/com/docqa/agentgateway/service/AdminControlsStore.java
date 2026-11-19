package com.docqa.agentgateway.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Kill Switch 元数据与实验账本的读写边界；Redis 实现在故障时的姿态由实现类决定。 */
public interface AdminControlsStore {

    Optional<KillSwitchMeta> killSwitchMeta();

    void writeKillSwitch(boolean active, UUID updatedBy, Instant now);

    /** 租户级 kill switch 的写入与元数据；键按 tenantId 隔离，不影响全局一键全停。 */
    Optional<KillSwitchMeta> tenantKillSwitchMeta(UUID tenantId);

    void writeTenantKillSwitch(UUID tenantId, boolean active, UUID updatedBy, Instant now);

    ExperimentBudgetView experimentBudget();
}
