package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.MessageHistoryRow;
import com.docqa.agentgateway.dto.StoredMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMessageRepository implements MessageRepository {

    private static final RowMapper<StoredMessage> ROW_MAPPER = JdbcMessageRepository::mapRow;

    private static final RowMapper<MessageHistoryRow> HISTORY_ROW_MAPPER =
            JdbcMessageRepository::mapHistoryRow;

    private final JdbcTemplate jdbcTemplate;

    public JdbcMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(
            UUID id,
            UUID conversationId,
            String role,
            String content,
            int tokenCount,
            String clientRequestId,
            String traceId,
            String metadataJson,
            Instant createdAt) {
        jdbcTemplate.update("""
                        INSERT INTO message (
                            id, conversation_id, role, content, token_count,
                            client_request_id, trace_id, metadata, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id.toString(),
                conversationId.toString(),
                role,
                content,
                tokenCount,
                clientRequestId,
                traceId,
                metadataJson,
                Timestamp.from(createdAt));
    }

    @Override
    public List<StoredMessage> findRecent(UUID conversationId, int limit) {
        List<StoredMessage> messages = new ArrayList<>(jdbcTemplate.query("""
                        SELECT id, role, content, created_at
                        FROM message
                        WHERE conversation_id = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT ?
                        """,
                ROW_MAPPER,
                conversationId.toString(),
                limit));
        Collections.reverse(messages);
        return messages;
    }

    @Override
    public List<MessageHistoryRow> findPage(UUID conversationId, Instant beforeCreatedAt, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("""
                        SELECT id, role, content, token_count, trace_id, created_at
                        FROM message
                        WHERE conversation_id = ?
                        """);
        List<Object> args = new ArrayList<>();
        args.add(conversationId.toString());
        if (beforeCreatedAt != null && beforeId != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            Timestamp cursorCreatedAt = Timestamp.from(beforeCreatedAt);
            args.add(cursorCreatedAt);
            args.add(cursorCreatedAt);
            args.add(beforeId.toString());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), HISTORY_ROW_MAPPER, args.toArray());
    }

    private static MessageHistoryRow mapHistoryRow(ResultSet rs, int rowNum) throws SQLException {
        return new MessageHistoryRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("role"),
                rs.getString("content"),
                rs.getInt("token_count"),
                rs.getString("trace_id"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static StoredMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        return new StoredMessage(
                UUID.fromString(rs.getString("id")),
                rs.getString("role"),
                rs.getString("content"),
                createdAt);
    }
}
