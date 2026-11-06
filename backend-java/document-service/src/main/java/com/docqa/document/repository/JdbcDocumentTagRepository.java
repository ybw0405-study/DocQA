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
    public Map<String, UUID> resolveOrCreate(UUID userId, List<TagName> tags, Instant now) {
        if (tags.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> resolved = new LinkedHashMap<>(findIds(userId, tags));
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
                insertTags(userId, missing, newIds, now);
                for (int index = 0; index < missing.size(); index++) {
                    resolved.put(missing.get(index).normalized(), newIds.get(index));
                }
            } catch (DuplicateKeyException ex) {
                Map<String, UUID> reread = findIds(userId, tags);
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
    public void replaceLinks(UUID documentId, List<UUID> tagIds, Instant now) {
        jdbcTemplate.update("DELETE FROM document_tag_link WHERE document_id = ?", documentId.toString());
        if (tagIds.isEmpty()) {
            return;
        }
        Timestamp created = Timestamp.from(now);
        String document = documentId.toString();
        jdbcTemplate.batchUpdate(
                "INSERT INTO document_tag_link (document_id, tag_id, created_at) VALUES (?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        ps.setString(1, document);
                        ps.setString(2, tagIds.get(index).toString());
                        ps.setTimestamp(3, created);
                    }

                    @Override
                    public int getBatchSize() {
                        return tagIds.size();
                    }
                });
    }

    @Override
    public List<String> findNames(UUID documentId) {
        return jdbcTemplate.query("""
                        SELECT t.name
                        FROM document_tag_link l
                        JOIN document_tag t ON t.id = l.tag_id
                        WHERE l.document_id = ?
                        ORDER BY t.created_at, t.id
                        """,
                (rs, rowNum) -> rs.getString("name"),
                documentId.toString());
    }

    @Override
    public Map<UUID, List<String>> findNamesByDocuments(List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = documentIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        Object[] args = documentIds.stream().map(UUID::toString).toArray();
        Map<UUID, List<String>> names = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT l.document_id, t.name FROM document_tag_link l"
                        + " JOIN document_tag t ON t.id = l.tag_id"
                        + " WHERE l.document_id IN (" + placeholders + ")"
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

    private Map<String, UUID> findIds(UUID userId, List<TagName> tags) {
        String placeholders = tags.stream().map(tag -> "?").collect(Collectors.joining(", "));
        Object[] args = new Object[tags.size() + 1];
        args[0] = userId.toString();
        for (int index = 0; index < tags.size(); index++) {
            args[index + 1] = tags.get(index).normalized();
        }
        Map<String, UUID> ids = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT id, normalized_name FROM document_tag"
                        + " WHERE user_id = ? AND normalized_name IN (" + placeholders + ")",
                rs -> {
                    ids.put(rs.getString("normalized_name"), UUID.fromString(rs.getString("id")));
                },
                args);
        return ids;
    }

    private void insertTags(UUID userId, List<TagName> tags, List<UUID> ids, Instant now) {
        String owner = userId.toString();
        Timestamp created = Timestamp.from(now);
        jdbcTemplate.batchUpdate("""
                        INSERT INTO document_tag (id, user_id, name, normalized_name, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        TagName tag = tags.get(index);
                        ps.setString(1, ids.get(index).toString());
                        ps.setString(2, owner);
                        ps.setString(3, tag.display());
                        ps.setString(4, tag.normalized());
                        ps.setTimestamp(5, created);
                    }

                    @Override
                    public int getBatchSize() {
                        return tags.size();
                    }
                });
    }
}
