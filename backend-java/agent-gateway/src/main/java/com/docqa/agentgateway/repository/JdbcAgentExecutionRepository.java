package com.docqa.agentgateway.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.domain.AgentExecution;
import com.docqa.agentgateway.domain.OutboxBacklog;
import com.docqa.agentgateway.domain.OutboxExecution;
import com.docqa.agentgateway.dto.AuditEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentExecutionRepository implements AgentExecutionRepository {

    private static final TypeReference<List<AuditEvent>> AUDIT_EVENT_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcAgentExecutionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(UUID tenantId, AgentExecution execution) {
        jdbcTemplate.update("""
                        INSERT INTO agent_execution (
                            tenant_id, id, trace_id, user_id, conversation_id, request_message_id,
                            response_message_id, status, budget_status, token_budget, token_used,
                            tool_calls_made, rounds_used, cache_hit, latency_ms, error_code, audit_outbox,
                            started_at, finished_at, recorded_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                tenantId.toString(),
                execution.id().toString(),
                execution.traceId(),
                execution.userId().toString(),
                execution.conversationId().toString(),
                nullableId(execution.requestMessageId()),
                nullableId(execution.responseMessageId()),
                execution.status(),
                execution.budgetStatus(),
                execution.tokenBudget(),
                execution.tokenUsed(),
                execution.toolCallsMade(),
                execution.roundsUsed(),
                execution.cacheHit(),
                execution.latencyMs(),
                execution.errorCode(),
                serialize(execution.auditOutbox()),
                Timestamp.from(execution.startedAt()),
                Timestamp.from(execution.finishedAt()),
                Timestamp.from(execution.recordedAt()));
    }

    @Override
    public List<OutboxExecution> findOutboxBatch(Instant afterRecordedAt, UUID afterId, int limit) {
        if (afterRecordedAt == null || afterId == null) {
            return jdbcTemplate.query("""
                            SELECT id, tenant_id, recorded_at, audit_outbox
                            FROM agent_execution
                            ORDER BY recorded_at, id
                            LIMIT ?
                            """,
                    rowMapper(),
                    limit);
        }
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, recorded_at, audit_outbox
                        FROM agent_execution
                        WHERE recorded_at > ? OR (recorded_at = ? AND id > ?)
                        ORDER BY recorded_at, id
                        LIMIT ?
                        """,
                rowMapper(),
                Timestamp.from(afterRecordedAt),
                Timestamp.from(afterRecordedAt),
                afterId.toString(),
                limit);
    }

    private RowMapper<OutboxExecution> rowMapper() {
        return (rs, rowNum) -> mapRow(rs);
    }

    @Override
    public OutboxBacklog outboxBacklog(Instant afterRecordedAt, UUID afterId) {
        if (afterRecordedAt == null || afterId == null) {
            return jdbcTemplate.queryForObject("""
                            SELECT COUNT(*) AS pending, MIN(recorded_at) AS oldest_recorded_at
                            FROM agent_execution
                            """,
                    backlogMapper());
        }
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) AS pending, MIN(recorded_at) AS oldest_recorded_at
                        FROM agent_execution
                        WHERE recorded_at > ? OR (recorded_at = ? AND id > ?)
                        """,
                backlogMapper(),
                Timestamp.from(afterRecordedAt),
                Timestamp.from(afterRecordedAt),
                afterId.toString());
    }

    private RowMapper<OutboxBacklog> backlogMapper() {
        return (rs, rowNum) -> {
            Timestamp oldest = rs.getTimestamp("oldest_recorded_at");
            return new OutboxBacklog(rs.getInt("pending"), oldest == null ? null : oldest.toInstant());
        };
    }

    private OutboxExecution mapRow(ResultSet rs) throws SQLException {
        return new OutboxExecution(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getTimestamp("recorded_at").toInstant(),
                deserialize(rs.getString("audit_outbox")));
    }

    private String nullableId(UUID id) {
        return id == null ? null : id.toString();
    }

    private String serialize(List<AuditEvent> events) {
        try {
            return objectMapper.writeValueAsString(events);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize audit outbox", ex);
        }
    }

    private List<AuditEvent> deserialize(String json) {
        try {
            return objectMapper.readValue(json, AUDIT_EVENT_LIST);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize audit outbox", ex);
        }
    }
}
