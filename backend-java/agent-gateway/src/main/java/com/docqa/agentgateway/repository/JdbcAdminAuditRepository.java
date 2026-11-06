package com.docqa.agentgateway.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdminAuditRepository implements AdminAuditRepository {

    public static final String AGENT_NAME = "admin_gateway";
    public static final String SOURCE = "JAVA";

    static final String INSERT_SQL = """
            INSERT INTO audit_event (
                id, trace_id, user_id, event_type, agent_name,
                source, severity, payload_redacted, occurred_at, ingested_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcAdminAuditRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(AdminAuditEntry entry) {
        jdbcTemplate.update(
                INSERT_SQL,
                entry.id().toString(),
                entry.traceId(),
                entry.userId().toString(),
                entry.eventType(),
                AGENT_NAME,
                SOURCE,
                entry.severity(),
                serializePayload(entry.payload()),
                Timestamp.from(entry.occurredAt()),
                Timestamp.from(entry.ingestedAt()));
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize admin audit payload", ex);
        }
    }
}
