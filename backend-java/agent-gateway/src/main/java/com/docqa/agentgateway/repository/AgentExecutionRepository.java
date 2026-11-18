package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.domain.AgentExecution;
import com.docqa.agentgateway.domain.OutboxBacklog;
import com.docqa.agentgateway.domain.OutboxExecution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AgentExecutionRepository {

    void insert(UUID tenantId, AgentExecution execution);

    List<OutboxExecution> findOutboxBatch(UUID tenantId, Instant afterRecordedAt, UUID afterId, int limit);

    /**
     * 游标之后（含）尚未投递的行数与其中最早一行的 {@code recorded_at}。
     *
     * <p>{@code audit_outbox} 是 {@code JSON NOT NULL} 且该表「追加写、禁止更新和删除」，
     * 所以没有任何字段可以被清空来标记「已投递」——待投递的唯一判据就是 relay 的游标位置。
     * 这也正是 §4.2 要这两个指标而不是只看 MQ lag 的原因：积压发生在 relay 追上 broker 之前。
     *
     * @param tenantId        租户；relay 无 HTTP 行上下文，P0 固定传默认租户
     * @param afterRecordedAt 游标时间；null 表示从表头开始（尚未 relay 过任何一批）
     * @param afterId         游标 id，与时间共同构成 keyset 位置
     */
    OutboxBacklog outboxBacklog(UUID tenantId, Instant afterRecordedAt, UUID afterId);
}
