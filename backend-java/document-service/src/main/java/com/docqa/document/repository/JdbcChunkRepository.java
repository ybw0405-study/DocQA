package com.docqa.document.repository;

import com.docqa.document.domain.ChunkEntry;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcChunkRepository implements ChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void upsertAll(UUID tenantId, List<ChunkEntry> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        String owner = tenantId.toString();
        jdbcTemplate.batchUpdate("""
                        INSERT INTO chunk (
                            tenant_id, id, document_id, chunk_index, vector_id, content_hash, token_count, index_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            vector_id = VALUES(vector_id),
                            content_hash = VALUES(content_hash),
                            token_count = VALUES(token_count)
                        """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        ChunkEntry chunk = chunks.get(index);
                        ps.setString(1, owner);
                        ps.setString(2, chunk.id().toString());
                        ps.setString(3, chunk.documentId().toString());
                        ps.setInt(4, chunk.chunkIndex());
                        ps.setString(5, chunk.vectorId());
                        ps.setString(6, chunk.contentHash());
                        ps.setInt(7, chunk.tokenCount());
                        ps.setString(8, chunk.indexVersion());
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
    }

    @Override
    public long count(UUID tenantId, UUID documentId, String indexVersion) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chunk WHERE tenant_id = ? AND document_id = ? AND index_version = ?",
                Long.class,
                tenantId.toString(),
                documentId.toString(),
                indexVersion);
        return count == null ? 0 : count;
    }

    @Override
    public Map<UUID, Long> countByDocuments(UUID tenantId, List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = documentIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        Object[] args = new Object[documentIds.size() + 1];
        args[0] = tenantId.toString();
        for (int index = 0; index < documentIds.size(); index++) {
            args[index + 1] = documentIds.get(index).toString();
        }
        List<Map.Entry<UUID, Long>> rows = jdbcTemplate.query(
                "SELECT c.document_id, COUNT(*) AS chunk_count FROM chunk c"
                        + " JOIN document d ON d.id = c.document_id AND d.tenant_id = c.tenant_id"
                        + " AND d.index_version = c.index_version"
                        + " WHERE c.tenant_id = ? AND c.document_id IN (" + placeholders + ")"
                        + " GROUP BY c.document_id",
                (rs, rowNum) -> Map.entry(UUID.fromString(rs.getString("document_id")), rs.getLong("chunk_count")),
                args);
        Map<UUID, Long> counts = new HashMap<>();
        for (Map.Entry<UUID, Long> row : rows) {
            counts.put(row.getKey(), row.getValue());
        }
        return Map.copyOf(counts);
    }

    @Override
    public int deleteByDocument(UUID tenantId, UUID documentId) {
        return jdbcTemplate.update(
                "DELETE FROM chunk WHERE tenant_id = ? AND document_id = ?",
                tenantId.toString(),
                documentId.toString());
    }

    @Override
    public int deleteByVersion(UUID tenantId, UUID documentId, String indexVersion) {
        return jdbcTemplate.update(
                "DELETE FROM chunk WHERE tenant_id = ? AND document_id = ? AND index_version = ?",
                tenantId.toString(),
                documentId.toString(),
                indexVersion);
    }
}
