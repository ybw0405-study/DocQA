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

    public AdminAiControls updateKillSwitch(UUID adminId, boolean active) {
        Instant now = clock.instant();
        controlsStore.writeKillSwitch(active, adminId, now);
        killSwitch.refresh(active);
        try {
            auditRepository.insert(new AdminAuditEntry(
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
