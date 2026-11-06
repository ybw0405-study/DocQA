package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.UsageDayPoint;
import com.docqa.agentgateway.dto.UsageStatusCount;
import com.docqa.agentgateway.dto.UsageTopUser;
import com.docqa.agentgateway.dto.UsageTotals;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * `agent_execution` 只读聚合（D-159）。
 *
 * <p>时间窗用 `started_at`，配合 V9 的 `idx_agent_execution_started`。
 * 日桶按 UTC：连接时区由 Hikari `connection-init-sql` 固定为 `+00:00`，
 * 因此 `DATE_FORMAT` 得到的就是 UTC 自然日。`failed` 只统计 `FAILED`，
 * 预算兜底单列，避免把「预算耗尽」误报为故障。</p>
 */
@Repository
public class JdbcAiUsageRepository implements AiUsageRepository {

    static final String TOTALS_SQL = """
            SELECT COUNT(*) AS questions,
                   COALESCE(SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS successes,
                   COALESCE(SUM(CASE WHEN status = 'BUDGET_EXHAUSTED' THEN 1 ELSE 0 END), 0) AS budget_exhausted,
                   COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failures,
                   COALESCE(SUM(token_used), 0) AS tokens,
                   COALESCE(SUM(tool_calls_made), 0) AS tool_calls,
                   COALESCE(SUM(rounds_used), 0) AS rounds,
                   COALESCE(SUM(CASE WHEN cache_hit THEN 1 ELSE 0 END), 0) AS cache_hits,
                   AVG(latency_ms) AS avg_latency_ms
            FROM agent_execution
            WHERE started_at >= ? AND started_at <= ?
            """;

    static final String BY_DAY_SQL = """
            SELECT DATE_FORMAT(started_at, '%Y-%m-%d') AS usage_day,
                   COUNT(*) AS questions,
                   COALESCE(SUM(token_used), 0) AS tokens,
                   COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failures
            FROM agent_execution
            WHERE started_at >= ? AND started_at <= ?
            GROUP BY usage_day
            ORDER BY usage_day
            """;

    static final String BY_STATUS_SQL = """
            SELECT status, COUNT(*) AS executions
            FROM agent_execution
            WHERE started_at >= ? AND started_at <= ?
            GROUP BY status
            """;

    static final String TOP_USERS_SQL = """
            SELECT e.user_id, u.username, COUNT(*) AS questions, COALESCE(SUM(e.token_used), 0) AS tokens
            FROM agent_execution e
            LEFT JOIN app_user u ON u.id = e.user_id
            WHERE e.started_at >= ? AND e.started_at <= ?
            GROUP BY e.user_id, u.username
            ORDER BY questions DESC, tokens DESC, e.user_id ASC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<UsageTotals> totalsMapper = this::mapTotals;
    private final RowMapper<UsageDayPoint> dayMapper = this::mapDay;
    private final RowMapper<UsageStatusCount> statusMapper = this::mapStatus;
    private final RowMapper<UsageTopUser> topUserMapper = this::mapTopUser;

    public JdbcAiUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UsageTotals totals(Instant from, Instant to) {
        List<UsageTotals> rows = jdbcTemplate.query(
                TOTALS_SQL, totalsMapper, Timestamp.from(from), Timestamp.from(to));
        return rows.isEmpty() ? emptyTotals() : rows.get(0);
    }

    @Override
    public List<UsageDayPoint> byDay(Instant from, Instant to) {
        return jdbcTemplate.query(BY_DAY_SQL, dayMapper, Timestamp.from(from), Timestamp.from(to));
    }

    @Override
    public List<UsageStatusCount> byStatus(Instant from, Instant to) {
        return jdbcTemplate.query(BY_STATUS_SQL, statusMapper, Timestamp.from(from), Timestamp.from(to));
    }

    @Override
    public List<UsageTopUser> topUsers(Instant from, Instant to, int limit) {
        return jdbcTemplate.query(
                TOP_USERS_SQL, topUserMapper, Timestamp.from(from), Timestamp.from(to), limit);
    }

    private UsageTotals mapTotals(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal avgLatency = rs.getBigDecimal("avg_latency_ms");
        return new UsageTotals(
                longValue(rs, "questions"),
                longValue(rs, "successes"),
                longValue(rs, "budget_exhausted"),
                longValue(rs, "failures"),
                longValue(rs, "tokens"),
                longValue(rs, "tool_calls"),
                longValue(rs, "rounds"),
                longValue(rs, "cache_hits"),
                avgLatency == null ? null : avgLatency.doubleValue());
    }

    private UsageDayPoint mapDay(ResultSet rs, int rowNum) throws SQLException {
        return new UsageDayPoint(
                rs.getString("usage_day"),
                longValue(rs, "questions"),
                longValue(rs, "tokens"),
                longValue(rs, "failures"));
    }

    private UsageStatusCount mapStatus(ResultSet rs, int rowNum) throws SQLException {
        return new UsageStatusCount(rs.getString("status"), longValue(rs, "executions"));
    }

    private UsageTopUser mapTopUser(ResultSet rs, int rowNum) throws SQLException {
        return new UsageTopUser(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("username"),
                longValue(rs, "questions"),
                longValue(rs, "tokens"));
    }

    private static long longValue(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static UsageTotals emptyTotals() {
        return new UsageTotals(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, null);
    }
}
