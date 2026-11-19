package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AdminAiControls;
import com.docqa.agentgateway.dto.ExperimentBudgetState;
import com.docqa.agentgateway.dto.KillSwitchState;
import com.docqa.agentgateway.repository.AdminAuditEntry;
import com.docqa.agentgateway.repository.AdminAuditRepository;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AdminAiControlsService {

    public static final String EVENT_KILL_SWITCH_ENABLED = "ADMIN_AI_KILL_SWITCH_ENABLED";
    public static final String EVENT_KILL_SWITCH_DISABLED = "ADMIN_AI_KILL_SWITCH_DISABLED";
    public static final String EVENT_TENANT_KILL_SWITCH_ENABLED = "ADMIN_AI_TENANT_KILL_SWITCH_ENABLED";
    public static final String EVENT_TENANT_KILL_SWITCH_DISABLED = "ADMIN_AI_TENANT_KILL_SWITCH_DISABLED";

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAiControlsService.class);

    private final KillSwitch killSwitch;
    private final AdminControlsStore controlsStore;
    private final AdminAuditRepository auditRepository;
    private final Clock clock;

    public AdminAiControlsService(
            KillSwitch killSwitch,
            AdminControlsStore controlsStore,
            AdminAuditRepository auditRepository,
            Clock clock) {
        this.killSwitch = killSwitch;
        this.controlsStore = controlsStore;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    public AdminAiControls getControls() {
        KillSwitchMeta meta = controlsStore.killSwitchMeta().orElse(null);
        ExperimentBudgetView budget = controlsStore.experimentBudget();
        boolean limitsKnown = budget.limitsKnown();
        return new AdminAiControls(
                new KillSwitchState(
                        killSwitch.isActive(),
                        meta == null ? null : meta.updatedAt(),
                        meta == null ? null : meta.updatedBy()),
                new ExperimentBudgetState(
                        budget.callsUsed(),
                        budget.tokensUsed(),
                        limitsKnown ? budget.callLimit() : null,
                        limitsKnown ? budget.tokenLimit() : null,
                        limitsKnown));
    }

    /** 某租户视角的 kill switch 有效状态（全局 OR 租户级），供管理面按租户展示。 */
    public KillSwitchState getTenantKillSwitch(UUID tenantId) {
        KillSwitchMeta meta = controlsStore.tenantKillSwitchMeta(tenantId).orElse(null);
        return new KillSwitchState(
                killSwitch.isActiveForTenant(tenantId),
                meta == null ? null : meta.updatedAt(),
                meta == null ? null : meta.updatedBy());
    }

    /**
     * 租户级 kill switch 只影响调用者自己租户（tenantId 取自 JWT，不接受请求体传参）。
     * 全局一键全停由 {@link #updateKillSwitch} 负责，两者在 Python 执行点按 OR 生效。
     */
    public KillSwitchState updateTenantKillSwitch(UUID tenantId, UUID adminId, boolean active) {
        Instant now = clock.instant();
        controlsStore.writeTenantKillSwitch(tenantId, active, adminId, now);
        recordTenantAudit(tenantId, adminId, active, now);
        return getTenantKillSwitch(tenantId);
    }

    private void recordTenantAudit(UUID tenantId, UUID adminId, boolean active, Instant now) {
        try {
            auditRepository.insert(tenantId, new AdminAuditEntry(
                    UUID.randomUUID(),
                    TraceIdContext.currentOrCreate(),
                    adminId,
                    active ? EVENT_TENANT_KILL_SWITCH_ENABLED : EVENT_TENANT_KILL_SWITCH_DISABLED,
                    active ? "WARN" : "INFO",
                    Map.of("tenantId", tenantId.toString()),
                    now,
                    now));
        } catch (RuntimeException ex) {
            LOGGER.error(
                    "Admin audit write failed after tenant kill switch change: tenantId={}, active={}, type={}",
                    tenantId,
                    active,
                    ex.getClass().getName());
            throw new BusinessException(
                    ErrorCode.COM_INTERNAL_001,
                    "租户 Kill Switch 状态已变更为" + (active ? "开启" : "关闭") + "，但管理审计写入失败；请重新读取状态。");
        }
    }

    /**
     * Kill Switch 本身是全局运行时开关，但管理审计必须落在调用者 JWT 的租户里，
     * 否则一条租户的管理操作会被记到默认租户账上。
     */
    public AdminAiControls updateKillSwitch(UUID tenantId, UUID adminId, boolean active) {
        Instant now = clock.instant();
        controlsStore.writeKillSwitch(active, adminId, now);
        killSwitch.refresh(active);
        try {
            auditRepository.insert(tenantId, new AdminAuditEntry(
                    UUID.randomUUID(),
                    TraceIdContext.currentOrCreate(),
                    adminId,
                    active ? EVENT_KILL_SWITCH_ENABLED : EVENT_KILL_SWITCH_DISABLED,
                    active ? "WARN" : "INFO",
                    Map.of(),
                    now,
                    now));
        } catch (RuntimeException ex) {
            // 开关是运行时事实、审计是留痕：状态已生效，不能把变更谎报成失败。
            LOGGER.error(
                    "Admin audit write failed after kill switch change: active={}, traceId={}, type={}",
                    active,
                    TraceIdContext.current(),
                    ex.getClass().getName());
            throw new BusinessException(
                    ErrorCode.COM_INTERNAL_001,
                    "Kill Switch 状态已变更为" + (active ? "开启" : "关闭") + "，但管理审计写入失败；请重新读取状态。");
        }
        return getControls();
    }
}
