package com.docqa.document.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * DQ-1：待确认向量删除的持久化 outbox。
 *
 * <p>与 {@link DocumentRepository} 分开是为了不把「重投队列」塞进文档状态机：
 * 一个文档可能因多次重建而同时存在多个待清理版本，且文档本身可能已经软删/不存在。
 * 每行自带 tenantId / userId，重投时无需反查文档表，也不会把租户归属算错。
 */
@Repository
public class VectorCleanupTaskRepository {

    /** 20 次仍未确认就停止重投，保留行供运维介入；绝不静默丢弃。 */
    public static final int MAX_ATTEMPTS = 20;

    public record Task(
            long id,
            UUID tenantId,
            UUID documentId,
            UUID userId,
            String indexVersion,
            String operation,
            int attempts) {
    }

    private final JdbcTemplate jdbcTemplate;

    public VectorCleanupTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 幂等入队：同一 (租户, 文档, 版本, 操作) 只留一行，重复投递不会放大。 */
    public void enqueue(
            UUID tenantId,
            UUID documentId,
            UUID userId,
            String indexVersion,
            String operation,
            Instant now) {
        jdbcTemplate.update("""
                        INSERT INTO vector_cleanup_task
                          (tenant_id, document_id, user_id, index_version, operation,
                           attempts, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                        ON DUPLICATE KEY UPDATE id = id
                        """,
                tenantId.toString(),
                documentId.toString(),
                userId.toString(),
                indexVersion,
                operation,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    /** 取一批到期且未超过重投上限的任务；updated_at 升序保证老任务优先。 */
    public List<Task> findPending(Instant updatedBefore, int limit) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, document_id, user_id, index_version, operation, attempts
                        FROM vector_cleanup_task
                        WHERE updated_at <= ? AND attempts < ?
                        ORDER BY updated_at ASC, id ASC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new Task(
                        rs.getLong("id"),
                        UUID.fromString(rs.getString("tenant_id")),
                        UUID.fromString(rs.getString("document_id")),
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("index_version"),
                        rs.getString("operation"),
                        rs.getInt("attempts")),
                Timestamp.from(updatedBefore),
                MAX_ATTEMPTS,
                limit);
    }

    /** 记录一次重投（无论发布成功与否都要记，否则下一轮会立刻重复投递同一行）。 */
    public void markAttempted(long id, Instant now) {
        jdbcTemplate.update("""
                        UPDATE vector_cleanup_task
                        SET attempts = attempts + 1, updated_at = ?
                        WHERE id = ?
                        """,
                Timestamp.from(now),
                id);
    }

    /** 只有收到成功确认才出队；FAILED 与发布失败一律保留。 */
    public void remove(UUID tenantId, UUID documentId, String indexVersion, String operation) {
        jdbcTemplate.update("""
                        DELETE FROM vector_cleanup_task
                        WHERE tenant_id = ? AND document_id = ? AND index_version = ? AND operation = ?
                        """,
                tenantId.toString(),
                documentId.toString(),
                indexVersion,
                operation);
    }
}
