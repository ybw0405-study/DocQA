package com.docqa.agentgateway.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.dto.AuditEventItem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 直接只读 `audit_event`（D-157）。
 *
 * <p>所有条件都参数化；排序固定 `occurred_at DESC, id DESC`，配合 V9 的
 * `idx_audit_occurred (occurred_at, id)` 让纯时间窗查询走索引而不是文件排序。</p>
 */
@Repository
public class JdbcAdminAuditQueryRepository implements AdminAuditQueryRepository {

    static final String PROJECTION = """
            SELECT id, trace_id, user_id, event_type, agent_name, source, severity,
                   payload_redacted, occurred_at
            FROM audit_event
            """;

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<AuditEventItem> rowMapper = this::mapRow;

    public JdbcAdminAuditQueryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AuditEventItem> findPage(
            AuditQueryFilter filter,
            Instant beforeOccurredAt,
            UUID beforeEventId,
            int limit) {
        StringBuilder sql = new StringBuilder(PROJECTION).append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (filter.traceId() != null) {
            sql.append(" AND trace_id = ?");
            args.add(filter.traceId());
        }
        if (filter.userId() != null) {
            sql.append(" AND user_id = ?");
            args.add(filter.userId().toString());
        }
        if (filter.eventType() != null) {
            sql.append(" AND event_type = ?");
            args.add(filter.eventType());
        }
        if (filter.from() != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND occurred_at <= ?");
            args.add(Timestamp.from(filter.to()));
        }
        if (beforeOccurredAt != null && beforeEventId != null) {
            Timestamp cursor = Timestamp.from(beforeOccurredAt);
            sql.append(" AND (occurred_at < ? OR (occurred_at = ? AND id < ?))");
            args.add(cursor);
            args.add(cursor);
            args.add(beforeEventId.toString());
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), args.toArray(), rowMapper);
    }

    private AuditEventItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        String agentName = rs.getString("agent_name");
        return new AuditEventItem(
                UUID.fromString(rs.getString("id")),
                rs.getString("trace_id"),
                UUID.fromString(rs.getString("user_id")),
                rs.getString("event_type"),
                agentName,
                rs.getString("source"),
                rs.getString("severity"),
                deserialize(rs.getString("payload_redacted")),
                toInstant(rs.getTimestamp("occurred_at")));
    }

    private Map<String, Object> deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, PAYLOAD_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize audit payload", ex);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
