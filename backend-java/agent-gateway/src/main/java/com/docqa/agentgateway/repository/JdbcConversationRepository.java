package com.docqa.agentgateway.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.dto.ConversationListRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcConversationRepository implements ConversationRepository {

    private static final TypeReference<List<UUID>> UUID_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<ConversationListRow> listRowMapper = this::mapListRow;

    public JdbcConversationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean existsById(UUID tenantId, UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE tenant_id = ? AND id = ?",
                Integer.class,
                tenantId.toString(),
                id.toString());
        return count != null && count > 0;
    }

    @Override
    public boolean existsByIdAndUser(UUID tenantId, UUID id, UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE tenant_id = ? AND id = ? AND user_id = ?",
                Integer.class,
                tenantId.toString(),
                id.toString(),
                userId.toString());
        return count != null && count > 0;
    }

    @Override
    public boolean createOwned(UUID tenantId, UUID id, UUID userId, String title, List<UUID> documentScopeIds,
            Instant now) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO conversation "
                            + "(tenant_id, id, user_id, title, document_scope_ids, last_message_at, created_at, "
                            + "updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, NULL, ?, ?)",
                    tenantId.toString(),
                    id.toString(),
                    userId.toString(),
                    title,
                    scopeJson(documentScopeIds),
                    Timestamp.from(now),
                    Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public void touch(UUID tenantId, UUID id, Instant now) {
        jdbcTemplate.update(
                "UPDATE conversation SET last_message_at = ? WHERE tenant_id = ? AND id = ?",
                Timestamp.from(now),
                tenantId.toString(),
                id.toString());
    }

    @Override
    public List<ConversationListRow> findUserPage(UUID tenantId, UUID userId, Instant beforeLastMessageAt,
            UUID beforeId, boolean beforeNullGroup, int limit) {
        StringBuilder sql = new StringBuilder("""
                        SELECT id, title, document_scope_ids, last_message_at, created_at
                        FROM conversation
                        WHERE tenant_id = ? AND user_id = ?
                        """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId.toString());
        args.add(userId.toString());
        if (beforeNullGroup) {
            sql.append(" AND (last_message_at IS NULL AND id < ?)");
            args.add(beforeId.toString());
        } else if (beforeLastMessageAt != null && beforeId != null) {
            sql.append(" AND ((last_message_at IS NOT NULL AND (last_message_at < ?");
            sql.append(" OR (last_message_at = ? AND id < ?))) OR last_message_at IS NULL)");
            Timestamp cursorLastMessageAt = Timestamp.from(beforeLastMessageAt);
            args.add(cursorLastMessageAt);
            args.add(cursorLastMessageAt);
            args.add(beforeId.toString());
        }
        sql.append(" ORDER BY (last_message_at IS NULL) ASC, last_message_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), listRowMapper, args.toArray());
    }

    private ConversationListRow mapListRow(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationListRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("title"),
                readScope(rs.getString("document_scope_ids")),
                toInstant(rs.getTimestamp("last_message_at")),
                toInstant(rs.getTimestamp("created_at")));
    }

    private List<UUID> readScope(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, UUID_LIST_TYPE);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new SQLException("Unable to parse conversation document scope", ex);
        }
    }

    private String scopeJson(List<UUID> documentScopeIds) {
        if (documentScopeIds == null || documentScopeIds.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(documentScopeIds);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize conversation document scope", ex);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
