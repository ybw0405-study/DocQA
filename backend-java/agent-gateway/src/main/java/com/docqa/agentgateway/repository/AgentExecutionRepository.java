package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.domain.AgentExecution;
import com.docqa.agentgateway.domain.OutboxBacklog;
import com.docqa.agentgateway.domain.OutboxExecution;
import com.docqa.agentgateway.domain.RelayCursor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AgentExecutionRepository {

    void insert(UUID tenantId, AgentExecution execution);

    List<OutboxExecution> findOutboxBatch(Instant afterRecordedAt, UUID afterId, int limit);

    /**
     * 游标之后（含）尚未投递的行数与其中最早一行的 {@code recorded_at}。
     *
     * <p>{@code audit_outbox} 是 {@code JSON NOT NULL} 且该表「追加写、禁止更新和删除」，
     * 所以没有任何字段可以被清空来标记「已投递」——待投递的唯一判据就是 relay 的游标位置。
     * 这也正是 §4.2 要这两个指标而不是只看 MQ lag 的原因：积压发生在 relay 追上 broker 之前。
     *
     * <p>relay 是无 HTTP 行上下文的全局投递任务，跨租户按单一全局游标扫描；
     * 租户边界由每行自带的 {@code tenant_id} 随消息下发，写侧仍逐行带租户谓词。
     *
     * @param afterRecordedAt 游标时间；null 表示从表头开始（尚未 relay 过任何一批）
     * @param afterId         游标 id，与时间共同构成 keyset 位置
     */
    OutboxBacklog outboxBacklog(Instant afterRecordedAt, UUID afterId);

    /** 读取持久化的 relay 游标；null 表示尚未投递过任何一批（从表头开始）。 */
    RelayCursor findRelayCursor();

    /** upsert 单行游标。游标是不可丢失的进度，存 DB 而非可被 LRU 驱逐的 Redis。 */
    void saveRelayCursor(Instant recordedAt, UUID id);
}
