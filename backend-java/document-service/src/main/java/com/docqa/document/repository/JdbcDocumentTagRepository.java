package com.docqa.document.repository;

import com.docqa.document.domain.TagName;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentTagRepository implements DocumentTagRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentTagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, UUID> resolveOrCreate(UUID tenantId, UUID userId, List<TagName> tags, Instant now) {
        if (tags.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> resolved = new LinkedHashMap<>(findIds(tenantId, userId, tags));
        List<TagName> missing = new ArrayList<>();
        List<UUID> newIds = new ArrayList<>();
        for (TagName tag : tags) {
            if (!resolved.containsKey(tag.normalized())) {
                missing.add(tag);
                newIds.add(UUID.randomUUID());
            }
        }
        if (!missing.isEmpty()) {
            try {
                insertTags(tenantId, userId, missing, newIds, now);
                for (int index = 0; index < missing.size(); index++) {
                    resolved.put(missing.get(index).normalized(), newIds.get(index));
                }
            } catch (DuplicateKeyException ex) {
                Map<String, UUID> reread = findIds(tenantId, userId, tags);
                for (TagName tag : missing) {
                    UUID id = reread.get(tag.normalized());
                    if (id == null) {
                        throw new IllegalStateException("Unable to resolve tag for user", ex);
                    }
                    resolved.put(tag.normalized(), id);
                }
            }
        }
        return Map.copyOf(resolved);
    }

    @Override
    public void replaceLinks(UUID tenantId, UUID documentId, List<UUID> tagIds, Instant now) {
        jdbcTemplate.update(
                "DELETE FROM document_tag_link WHERE tenant_id = ? AND document_id = ?",
                tenantId.toString(),
                documentId.toString());
        if (tagIds.isEmpty()) {
            return;
        }
        Timestamp created = Timestamp.from(now);
        String owner = tenantId.toString();
        String document = documentId.toString();
        jdbcTemplate.batchUpdate(
                "INSERT INTO document_tag_link (tenant_id, document_id, tag_id, created_at) VALUES (?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        ps.setString(1, owner);
                        ps.setString(2, document);
                        ps.setString(3, tagIds.get(index).toString());
                        ps.setTimestamp(4, created);
                    }

                    @Override
                    public int getBatchSize() {
                        return tagIds.size();
                    }
                });
    }

    @Override
    public List<String> findNames(UUID tenantId, UUID documentId) {
        return jdbcTemplate.query("""
                        SELECT t.name
                        FROM document_tag_link l
                        JOIN document_tag t ON t.id = l.tag_id
                        WHERE l.tenant_id = ? AND l.document_id = ? AND t.tenant_id = l.tenant_id
                        ORDER BY t.created_at, t.id
                        """,
                (rs, rowNum) -> rs.getString("name"),
                tenantId.toString(),
                documentId.toString());
    }

    @Override
    public Map<UUID, List<String>> findNamesByDocuments(UUID tenantId, List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = documentIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        Object[] args = new Object[documentIds.size() + 1];
        args[0] = tenantId.toString();
        for (int index = 0; index < documentIds.size(); index++) {
            args[index + 1] = documentIds.get(index).toString();
        }
        Map<UUID, List<String>> names = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT l.document_id, t.name FROM document_tag_link l"
                        + " JOIN document_tag t ON t.id = l.tag_id"
                        + " WHERE l.tenant_id = ? AND l.document_id IN (" + placeholders + ")"
                        + " AND t.tenant_id = l.tenant_id"
                        + " ORDER BY t.created_at, t.id",
                rs -> {
                    UUID documentId = UUID.fromString(rs.getString("document_id"));
                    names.computeIfAbsent(documentId, key -> new ArrayList<>()).add(rs.getString("name"));
                },
                args);
        Map<UUID, List<String>> copy = new LinkedHashMap<>();
        names.forEach((documentId, list) -> copy.put(documentId, List.copyOf(list)));
        return Map.copyOf(copy);
    }

    private Map<String, UUID> findIds(UUID tenantId, UUID userId, List<TagName> tags) {
        String placeholders = tags.stream().map(tag -> "?").collect(Collectors.joining(", "));
        Object[] args = new Object[tags.size() + 2];
        args[0] = tenantId.toString();
        args[1] = userId.toString();
        for (int index = 0; index < tags.size(); index++) {
            args[index + 2] = tags.get(index).normalized();
        }
        Map<String, UUID> ids = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT id, normalized_name FROM document_tag"
                        + " WHERE tenant_id = ? AND user_id = ? AND normalized_name IN (" + placeholders + ")",
                rs -> {
                    ids.put(rs.getString("normalized_name"), UUID.fromString(rs.getString("id")));
                },
                args);
        return ids;
    }

    private void insertTags(UUID tenantId, UUID userId, List<TagName> tags, List<UUID> ids, Instant now) {
        String owner = tenantId.toString();
        String user = userId.toString();
        Timestamp created = Timestamp.from(now);
        jdbcTemplate.batchUpdate("""
                        INSERT INTO document_tag (tenant_id, id, user_id, name, normalized_name, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        TagName tag = tags.get(index);
                        ps.setString(1, owner);
                        ps.setString(2, ids.get(index).toString());
                        ps.setString(3, user);
                        ps.setString(4, tag.display());
                        ps.setString(5, tag.normalized());
                        ps.setTimestamp(6, created);
                    }

                    @Override
                    public int getBatchSize() {
                        return tags.size();
                    }
                });
    }
}
