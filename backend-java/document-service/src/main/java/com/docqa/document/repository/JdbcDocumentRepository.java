package com.docqa.document.repository;

import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentRepository implements DocumentRepository {

    private static final String COLUMNS = """
            id, user_id, file_name, title, content_type, size_bytes, md5, object_key, status,
            retry_count, max_retry, error_code, error_message, index_version, pending_index_version,
            vector_rows, vector_confirmed_at, parsing_started_at, indexed_at, deleted_at,
            created_at, updated_at
            """;

    private static final String INDEXED_ASSIGNMENTS = """
            status = 'INDEXED',
                indexed_at = ?,
                error_code = NULL,
                error_message = NULL""";

    private static final String PROMOTE_ASSIGNMENTS = """
            status = 'INDEXED',
                indexed_at = ?,
                error_code = NULL,
                error_message = NULL,
                index_version = pending_index_version,
                pending_index_version = NULL""";

    private static final RowMapper<DocumentRow> ROW_MAPPER = JdbcDocumentRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<DocumentRow> findById(UUID tenantId, UUID id) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM document WHERE tenant_id = ? AND id = ?",
                        ROW_MAPPER,
                        tenantId.toString(),
                        id.toString())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<DocumentRow> findByIdAndUser(UUID tenantId, UUID id, UUID userId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS
                                + " FROM document WHERE tenant_id = ? AND id = ? AND user_id = ? AND deleted_at IS NULL",
                        ROW_MAPPER,
                        tenantId.toString(),
                        id.toString(),
                        userId.toString())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<DocumentRow> findByUserAndMd5(UUID tenantId, UUID userId, String md5) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM document WHERE tenant_id = ? AND user_id = ? AND md5 = ?",
                        ROW_MAPPER,
                        tenantId.toString(),
                        userId.toString(),
                        md5)
                .stream()
                .findFirst();
    }

    @Override
    public void insert(UUID tenantId, DocumentRow document) {
        jdbcTemplate.update("""
                        INSERT INTO document (
                            tenant_id, id, user_id, file_name, title, content_type, size_bytes, md5, object_key, status,
                            retry_count, max_retry, error_code, error_message, index_version,
                            pending_index_version, vector_rows, vector_confirmed_at,
                            parsing_started_at, indexed_at, deleted_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                tenantId.toString(),
                document.id().toString(),
                document.userId().toString(),
                document.fileName(),
                document.title(),
                document.contentType(),
                document.sizeBytes(),
                document.md5(),
                document.objectKey(),
                document.status().name(),
                document.retryCount(),
                document.maxRetry(),
                document.errorCode(),
                document.errorMessage(),
                document.indexVersion(),
                document.pendingIndexVersion(),
                document.vectorRows(),
                toTimestamp(document.vectorConfirmedAt()),
                toTimestamp(document.parsingStartedAt()),
                toTimestamp(document.indexedAt()),
                toTimestamp(document.deletedAt()),
                Timestamp.from(document.createdAt()),
                Timestamp.from(document.updatedAt()));
    }

    @Override
    public int deleteByIdAndUser(UUID tenantId, UUID id, UUID userId) {
        return jdbcTemplate.update(
                "DELETE FROM document WHERE tenant_id = ? AND id = ? AND user_id = ? AND status = 'UPLOADED'",
                tenantId.toString(),
                id.toString(),
                userId.toString());
    }

    @Override
    public int markParsing(UUID tenantId, UUID id, UUID userId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE document
                        SET status = 'PARSING',
                            parsing_started_at = ?,
                            error_code = NULL,
                            error_message = NULL
                        WHERE tenant_id = ? AND id = ? AND user_id = ? AND status = 'UPLOADED' AND deleted_at IS NULL
                        """,
                Timestamp.from(now),
                tenantId.toString(),
                id.toString(),
                userId.toString());
    }

    @Override
    public int markIndexed(UUID tenantId, UUID id, String indexVersion, Long vectorRows, Instant now) {
        return applyIndexed(tenantId, INDEXED_ASSIGNMENTS,
                "id = ? AND status = 'PARSING' AND index_version = ? AND deleted_at IS NULL",
                List.of(id.toString(), indexVersion),
                now,
                vectorRows);
    }

    @Override
    public int promotePendingIndexVersion(UUID tenantId, UUID id, String pendingIndexVersion, Long vectorRows,
            Instant now) {
        return applyIndexed(tenantId, PROMOTE_ASSIGNMENTS,
                "id = ? AND pending_index_version = ? AND deleted_at IS NULL",
                List.of(id.toString(), pendingIndexVersion),
                now,
                vectorRows);
    }

    @Override
    public int recordVectorConfirmation(UUID tenantId, UUID id, long remainingRows, Instant confirmedAt) {
        return jdbcTemplate.update("""
                        UPDATE document
                        SET vector_rows = ?,
                            vector_confirmed_at = ?
                        WHERE tenant_id = ? AND id = ? AND deleted_at IS NOT NULL
                        """,
                remainingRows,
                Timestamp.from(confirmedAt),
                tenantId.toString(),
                id.toString());
    }

    @Override
    public int markFailed(UUID tenantId, UUID id, String errorCode, String errorMessage) {
        return jdbcTemplate.update("""
                        UPDATE document
                        SET status = 'FAILED',
                            error_code = ?,
                            error_message = ?
                        WHERE tenant_id = ? AND id = ? AND status = 'PARSING' AND deleted_at IS NULL
                        """,
                errorCode,
                errorMessage,
                tenantId.toString(),
                id.toString());
    }

    @Override
    public int markDead(UUID tenantId, UUID id, String errorCode, String errorMessage) {
        return jdbcTemplate.update("""
                        UPDATE document
                        SET status = 'DEAD',
                            error_code = ?,
                            error_message = ?
                        WHERE tenant_id = ? AND id = ? AND status IN ('PARSING', 'FAILED') AND deleted_at IS NULL
                        """,
                errorCode,
                errorMessage,
                tenantId.toString(),
                id.toString());
    }

    @Override
    public int incrementRetryAndMarkParsing(UUID tenantId, UUID id, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE document
                        SET retry_count = retry_count + 1,
                            status = 'PARSING',
                            parsing_started_at = ?,
                            error_code = NULL,
                            error_message = NULL
                        WHERE tenant_id = ? AND id = ?
                          AND status IN ('PARSING', 'FAILED')
                          AND retry_count < max_retry
                          AND deleted_at IS NULL
                        """,
                Timestamp.from(now),
                tenantId.toString(),
                id.toString());
    }

    @Override
    public int updateTitle(UUID tenantId, UUID id, UUID userId, String title) {
        return jdbcTemplate.update("""
                        UPDATE document
                        SET title = ?
                        WHERE tenant_id = ? AND id = ? AND user_id = ? AND deleted_at IS NULL
                        """,
                title,
                tenantId.toString(),
                id.toString(),
                userId.toString());
    }

    @Override
    public int softDelete(UUID tenantId, UUID id, UUID userId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE document
                        SET deleted_at = ?
                        WHERE tenant_id = ? AND id = ? AND user_id = ? AND deleted_at IS NULL AND status <> 'PARSING'
                        """,
                Timestamp.from(now),
                tenantId.toString(),
                id.toString(),
                userId.toString());
    }

    @Override
    public int resetRetryAndMarkParsing(UUID tenantId, UUID id, UUID userId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE document
                        SET status = 'PARSING',
                            retry_count = 0,
                            parsing_started_at = ?,
                            error_code = NULL,
                            error_message = NULL
                        WHERE tenant_id = ? AND id = ? AND user_id = ?
                          AND status IN ('FAILED', 'DEAD')
                          AND deleted_at IS NULL
                        """,
                Timestamp.from(now),
                tenantId.toString(),
                id.toString(),
                userId.toString());
    }

    @Override
    public int beginReindex(UUID tenantId, UUID id, UUID userId, String pendingIndexVersion, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE document
                        SET pending_index_version = ?,
                            retry_count = 0,
                            parsing_started_at = ?,
                            error_code = NULL,
                            error_message = NULL
                        WHERE tenant_id = ? AND id = ? AND user_id = ?
                          AND status = 'INDEXED'
                          AND pending_index_version IS NULL
                          AND deleted_at IS NULL
                        """,
                pendingIndexVersion,
                Timestamp.from(now),
                tenantId.toString(),
                id.toString(),
                userId.toString());
    }

    @Override
    public int abortReindex(UUID tenantId, UUID id, String pendingIndexVersion, String errorCode, String errorMessage) {
        return jdbcTemplate.update("""
                        UPDATE document
                        SET pending_index_version = NULL,
                            error_code = ?,
                            error_message = ?
                        WHERE tenant_id = ? AND id = ?
                          AND pending_index_version = ?
                          AND deleted_at IS NULL
                        """,
                errorCode,
                errorMessage,
                tenantId.toString(),
                id.toString(),
                pendingIndexVersion);
    }

    @Override
    public List<DocumentRow> findStaleParsing(UUID tenantId, Instant threshold, int limit) {
        return jdbcTemplate.query("""
                        SELECT %s
                        FROM document
                        WHERE tenant_id = ? AND status = 'PARSING' AND parsing_started_at < ? AND deleted_at IS NULL
                        ORDER BY parsing_started_at
                        LIMIT ?
                        """.formatted(COLUMNS),
                ROW_MAPPER,
                tenantId.toString(),
                Timestamp.from(threshold),
                limit);
    }

    @Override
    public List<DocumentRow> findStaleReindex(UUID tenantId, Instant threshold, int limit) {
        return jdbcTemplate.query("""
                        SELECT %s
                        FROM document
                        WHERE tenant_id = ? AND pending_index_version IS NOT NULL
                          AND parsing_started_at < ?
                          AND deleted_at IS NULL
                        ORDER BY parsing_started_at
                        LIMIT ?
                        """.formatted(COLUMNS),
                ROW_MAPPER,
                tenantId.toString(),
                Timestamp.from(threshold),
                limit);
    }

    @Override
    public List<DocumentRow> findUnconfirmedDeletions(UUID tenantId, Instant deletedBefore, int limit) {
        return jdbcTemplate.query("""
                        SELECT %s
                        FROM document
                        WHERE tenant_id = ? AND deleted_at IS NOT NULL
                          AND deleted_at < ?
                          AND (vector_confirmed_at IS NULL OR vector_confirmed_at < deleted_at)
                        ORDER BY deleted_at
                        LIMIT ?
                        """.formatted(COLUMNS),
                ROW_MAPPER,
                tenantId.toString(),
                Timestamp.from(deletedBefore),
                limit);
    }

    @Override
    public List<DocumentRow> findUserPage(UUID tenantId, UUID userId, DocumentStatus status, String keyword,
            String normalizedTagName, Instant beforeCreatedAt, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(COLUMNS)
                .append(" FROM document d")
                .append(" WHERE d.tenant_id = ? AND d.user_id = ? AND d.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(tenantId.toString());
        args.add(userId.toString());
        if (status != null) {
            sql.append(" AND d.status = ?");
            args.add(status.name());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (d.file_name LIKE ? OR d.title LIKE ?)");
            String pattern = likePattern(keyword);
            args.add(pattern);
            args.add(pattern);
        }
        if (normalizedTagName != null && !normalizedTagName.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM document_tag_link l JOIN document_tag t ON t.id = l.tag_id")
                    .append(" WHERE l.document_id = d.id AND l.tenant_id = ? AND t.tenant_id = ?")
                    .append(" AND t.user_id = ? AND t.normalized_name = ?)");
            args.add(tenantId.toString());
            args.add(tenantId.toString());
            args.add(userId.toString());
            args.add(normalizedTagName);
        }
        if (beforeCreatedAt != null && beforeId != null) {
            sql.append(" AND (d.created_at < ? OR (d.created_at = ? AND d.id < ?))");
            Timestamp cursorCreatedAt = Timestamp.from(beforeCreatedAt);
            args.add(cursorCreatedAt);
            args.add(cursorCreatedAt);
            args.add(beforeId.toString());
        }
        sql.append(" ORDER BY d.created_at DESC, d.id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    private int applyIndexed(UUID tenantId, String assignments, String guard, List<Object> guardArgs, Instant now,
            Long vectorRows) {
        StringBuilder sql = new StringBuilder("UPDATE document SET ")
                .append(assignments);
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        if (vectorRows != null) {
            sql.append(", vector_rows = ?, vector_confirmed_at = ?");
            args.add(vectorRows);
            args.add(Timestamp.from(now));
        }
        sql.append(" WHERE tenant_id = ? AND ").append(guard);
        args.add(tenantId.toString());
        args.addAll(guardArgs);
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    private static String likePattern(String keyword) {
        StringBuilder pattern = new StringBuilder(keyword.length() + 2);
        pattern.append('%');
        for (int index = 0; index < keyword.length(); index++) {
            char character = keyword.charAt(index);
            if (character == '\\' || character == '%' || character == '_') {
                pattern.append('\\');
            }
            pattern.append(character);
        }
        return pattern.append('%').toString();
    }

    private static DocumentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        long rawVectorRows = rs.getLong("vector_rows");
        boolean vectorRowsMissing = rs.wasNull();
        return new DocumentRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("user_id")),
                rs.getString("file_name"),
                rs.getString("title"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("md5"),
                rs.getString("object_key"),
                DocumentStatus.from(rs.getString("status")),
                rs.getInt("retry_count"),
                rs.getInt("max_retry"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("index_version"),
                rs.getString("pending_index_version"),
                vectorRowsMissing ? null : Long.valueOf(rawVectorRows),
                toInstant(rs.getTimestamp("vector_confirmed_at")),
                toInstant(rs.getTimestamp("parsing_started_at")),
                toInstant(rs.getTimestamp("indexed_at")),
                toInstant(rs.getTimestamp("deleted_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
