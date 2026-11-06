package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.UsageDayPoint;
import com.docqa.agentgateway.dto.UsageStatusCount;
import com.docqa.agentgateway.dto.UsageTopUser;
import com.docqa.agentgateway.dto.UsageTotals;
import java.time.Instant;
import java.util.List;

/** 管理面用量聚合的只读仓库，数据源是 `agent_execution` 的终态快照（D-159）。 */
public interface AiUsageRepository {

    UsageTotals totals(Instant from, Instant to);

    List<UsageDayPoint> byDay(Instant from, Instant to);

    List<UsageStatusCount> byStatus(Instant from, Instant to);

    List<UsageTopUser> topUsers(Instant from, Instant to, int limit);
}
