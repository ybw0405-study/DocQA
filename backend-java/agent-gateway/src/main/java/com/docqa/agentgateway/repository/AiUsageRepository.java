package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.UsageDayPoint;
import com.docqa.agentgateway.dto.UsageStatusCount;
import com.docqa.agentgateway.dto.UsageTopUser;
import com.docqa.agentgateway.dto.UsageTotals;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 管理面用量聚合的只读仓库，数据源是 `agent_execution` 的终态快照。 */
public interface AiUsageRepository {

    UsageTotals totals(UUID tenantId, Instant from, Instant to);

    List<UsageDayPoint> byDay(UUID tenantId, Instant from, Instant to);

    List<UsageStatusCount> byStatus(UUID tenantId, Instant from, Instant to);

    List<UsageTopUser> topUsers(UUID tenantId, Instant from, Instant to, int limit);
}
