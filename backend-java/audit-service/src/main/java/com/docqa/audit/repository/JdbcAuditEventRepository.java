package com.docqa.audit.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.audit.domain.AuditEventRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditEventRepository implements AuditEventRepository {

    static final String INSERT_SQL = """
            INSERT INTO audit_event (
                tenant_id, id, trace_id, user_id, event_type, agent_name,
                source, severity, payload_redacted, occurred_at, ingested_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    static final String SELECT_BY_TRACE_SQL = """
            SELECT id, trace_id, user_id, event_type, agent_name,
                   source, severity, payload_redacted, occurred_at, ingested_at
            FROM audit_event
            WHERE tenant_id = ? AND trace_id = ?
            ORDER BY occurred_at ASC, id ASC
            LIMIT ?
            """;

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcAuditEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(UUID tenantId, AuditEventRow event) {
        jdbcTemplate.update(
                INSERT_SQL,
                tenantId.toString(),
                event.id().toString(),
                event.traceId(),
                event.userId().toString(),
                event.eventType(),
                event.agentName(),
                event.source(),
                event.severity(),
                serializePayload(event.payloadRedacted()),
                Timestamp.from(event.occurredAt()),
                Timestamp.from(event.ingestedAt()));
    }

    @Override
    public List<AuditEventRow> findByTraceId(UUID tenantId, String traceId, int limit) {
        return jdbcTemplate.query(
                SELECT_BY_TRACE_SQL,
                (resultSet, rowNumber) -> mapRow(resultSet),
                tenantId.toString(),
                traceId,
                limit);
    }

    private AuditEventRow mapRow(ResultSet resultSet) throws SQLException {
        return new AuditEventRow(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("trace_id"),
                UUID.fromString(resultSet.getString("user_id")),
                resultSet.getString("event_type"),
                resultSet.getString("agent_name"),
                resultSet.getString("source"),
                resultSet.getString("severity"),
                deserializePayload(resultSet.getString("payload_redacted")),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getTimestamp("ingested_at").toInstant());
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize audit payload", ex);
        }
    }

    private Map<String, Object> deserializePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload, PAYLOAD_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored audit payload is not valid JSON", ex);
        }
    }
}
