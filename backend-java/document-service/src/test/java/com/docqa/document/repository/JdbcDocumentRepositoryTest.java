package com.docqa.document.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.domain.VectorConsistency;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcDocumentRepositoryTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CURSOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant CURSOR_AT = Instant.parse("2026-10-20T10:15:00Z");
    private static final Instant NOW = Instant.parse("2026-10-30T06:00:00Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcDocumentRepository repository = new JdbcDocumentRepository(jdbcTemplate);

    @Test
    void firstPageBindsOnlyTheUserAndTheLimitAndAlwaysExcludesDeleted() {
        repository.findUserPage(TENANT_ID, USER_ID, null, null, null, null, null, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(TENANT_ID.toString()), eq(USER_ID.toString()), eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM document d WHERE d.tenant_id = ? AND d.user_id = ?");
        assertThat(sql).contains("AND d.deleted_at IS NULL");
        assertThat(sql).contains("ORDER BY d.created_at DESC, d.id DESC LIMIT ?");
        assertThat(sql).doesNotContain("d.status = ?");
        assertThat(sql).doesNotContain("created_at < ?");
        assertThat(sql).doesNotContain(USER_ID.toString());
    }

    @Test
    void statusFilterAndKeysetCursorAreBothBoundAsPlaceholders() {
        repository.findUserPage(TENANT_ID, USER_ID, DocumentStatus.FAILED, null, null, CURSOR_AT, CURSOR_ID, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(TENANT_ID.toString()),
                eq(USER_ID.toString()),
                eq("FAILED"),
                eq(Timestamp.from(CURSOR_AT)),
                eq(Timestamp.from(CURSOR_AT)),
                eq(CURSOR_ID.toString()),
                eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("AND d.status = ?");
        assertThat(sql).contains("AND (d.created_at < ? OR (d.created_at = ? AND d.id < ?))");
        assertThat(sql).doesNotContain(CURSOR_AT.toString());
        assertThat(sql).doesNotContain(CURSOR_ID.toString());
    }

    @Test
    void keywordFilterBindsOneEscapedPatternTwice() {
        repository.findUserPage(TENANT_ID, USER_ID, null, "100%_搞定\\期末", null, null, null, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(TENANT_ID.toString()),
                eq(USER_ID.toString()),
                eq("%100\\%\\_搞定\\\\期末%"),
                eq("%100\\%\\_搞定\\\\期末%"),
                eq(21));
        assertThat(sqlCaptor.getValue()).contains("AND (d.file_name LIKE ? OR d.title LIKE ?)");
    }

    @Test
    void tagFilterResolvesByNameInsideTheOwningUserOnly() {
        repository.findUserPage(TENANT_ID, USER_ID, null, null, "考研数学", null, null, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(TENANT_ID.toString()),
                eq(USER_ID.toString()),
                eq(TENANT_ID.toString()),
                eq(TENANT_ID.toString()),
                eq(USER_ID.toString()),
                eq("考研数学"),
                eq(21));
        assertThat(sqlCaptor.getValue())
                .contains("AND EXISTS (SELECT 1 FROM document_tag_link l JOIN document_tag t ON t.id = l.tag_id")
                .contains("WHERE l.document_id = d.id AND l.tenant_id = ? AND t.tenant_id = ? AND t.user_id = ? AND t.normalized_name = ?");
    }

    @Test
    void halfCursorIsIgnoredInsteadOfLeakingTheWholeTable() {
        repository.findUserPage(TENANT_ID, USER_ID, null, null, null, CURSOR_AT, null, 21);

        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(TENANT_ID.toString()), eq(USER_ID.toString()), eq(21));
    }

    @Test
    void markIndexedWritesVectorColumnsOnlyWhenTheEventCarriesThem() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        repository.markIndexed(TENANT_ID, DOCUMENT_ID, "v2", 42L, NOW);
        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                eq(Timestamp.from(NOW)),
                eq(42L),
                eq(Timestamp.from(NOW)),
                eq(TENANT_ID.toString()),
                eq(DOCUMENT_ID.toString()),
                eq("v2"));
        assertThat(sqlCaptor.getValue())
                .contains("vector_rows = ?, vector_confirmed_at = ?")
                .contains("AND status = 'PARSING' AND index_version = ? AND deleted_at IS NULL");

        ArgumentCaptor<String> withoutRows = ArgumentCaptor.forClass(String.class);
        repository.markIndexed(TENANT_ID, DOCUMENT_ID, "v2", null, NOW);
        verify(jdbcTemplate).update(
                withoutRows.capture(),
                eq(Timestamp.from(NOW)),
                eq(TENANT_ID.toString()),
                eq(DOCUMENT_ID.toString()),
                eq("v2"));
        assertThat(withoutRows.getValue()).doesNotContain("vector_rows");
    }

    @Test
    void promotePendingIndexVersionGuardsOnThePendingColumnNotOnStatus() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        repository.promotePendingIndexVersion(TENANT_ID, DOCUMENT_ID, "v3", 7L, NOW);

        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                eq(Timestamp.from(NOW)),
                eq(7L),
                eq(Timestamp.from(NOW)),
                eq(TENANT_ID.toString()),
                eq(DOCUMENT_ID.toString()),
                eq("v3"));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("index_version = pending_index_version");
        assertThat(sql).contains("pending_index_version = NULL");
        assertThat(sql).contains("WHERE tenant_id = ? AND id = ?");
        assertThat(sql).contains("AND pending_index_version = ?");
        assertThat(sql).contains("AND deleted_at IS NULL");
        assertThat(sql).doesNotContain("status = 'PARSING'");
    }

    @Test
    void abortReindexKeepsIndexedStatusAndClearsOnlyThePendingVersion() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        repository.abortReindex(TENANT_ID, DOCUMENT_ID, "v3", "DOC-PARSE-001", "重建索引失败，已保留原索引版本，可稍后再次发起重建。");

        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                eq("DOC-PARSE-001"),
                eq("重建索引失败，已保留原索引版本，可稍后再次发起重建。"),
                eq(TENANT_ID.toString()),
                eq(DOCUMENT_ID.toString()),
                eq("v3"));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("SET pending_index_version = NULL");
        assertThat(sql).doesNotContain("status");
        assertThat(sql).contains("AND pending_index_version = ?");
        assertThat(sql).contains("AND deleted_at IS NULL");
    }

    @Test
    void beginReindexKeepsTheDocumentIndexed() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        repository.beginReindex(TENANT_ID, DOCUMENT_ID, USER_ID, "v2", NOW);

        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                eq("v2"),
                eq(Timestamp.from(NOW)),
                eq(TENANT_ID.toString()),
                eq(DOCUMENT_ID.toString()),
                eq(USER_ID.toString()));
        String sql = sqlCaptor.getValue();
        assertThat(sql).doesNotContain("status = 'PARSING'");
        assertThat(sql).contains("AND status = 'INDEXED'");
        assertThat(sql).contains("AND pending_index_version IS NULL");
    }

    @Test
    void vectorConfirmationIsOnlyWritableForDeletedDocuments() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        repository.recordVectorConfirmation(TENANT_ID, DOCUMENT_ID, 0L, NOW);

        verify(jdbcTemplate).update(
                sqlCaptor.capture(), eq(0L), eq(Timestamp.from(NOW)), eq(TENANT_ID.toString()), eq(DOCUMENT_ID.toString()));
        assertThat(sqlCaptor.getValue()).contains("WHERE tenant_id = ? AND id = ? AND deleted_at IS NOT NULL");
    }

    @Test
    void softDeleteAndManualRetryCarryTheirOwnStateGuards() {
        repository.softDelete(TENANT_ID, DOCUMENT_ID, USER_ID, NOW);
        repository.resetRetryAndMarkParsing(TENANT_ID, DOCUMENT_ID, USER_ID, NOW);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(
                sqlCaptor.capture(), eq(Timestamp.from(NOW)), eq(TENANT_ID.toString()), eq(DOCUMENT_ID.toString()), eq(USER_ID.toString()));
        List<String> statements = sqlCaptor.getAllValues();
        String deleteSql = statements.stream().filter(sql -> sql.contains("deleted_at = ?"))
                .findFirst().orElseThrow();
        String retrySql = statements.stream().filter(sql -> sql.contains("retry_count = 0"))
                .findFirst().orElseThrow();

        assertThat(deleteSql)
                .contains("AND deleted_at IS NULL")
                .contains("AND status <> 'PARSING'");
        assertThat(retrySql)
                .contains("status = 'PARSING'")
                .contains("AND status IN ('FAILED', 'DEAD')");
    }

    @Test
    void ownedReadsFilterDeletedRowsButMd5LookupDoesNot() {
        ArgumentCaptor<String> owned = ArgumentCaptor.forClass(String.class);
        repository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID);
        verify(jdbcTemplate).query(
                owned.capture(), any(RowMapper.class), eq(TENANT_ID.toString()), eq(DOCUMENT_ID.toString()), eq(USER_ID.toString()));
        assertThat(owned.getValue()).contains("AND deleted_at IS NULL");

        ArgumentCaptor<String> byMd5 = ArgumentCaptor.forClass(String.class);
        repository.findByUserAndMd5(TENANT_ID, USER_ID, "0123456789abcdef0123456789abcdef");
        verify(jdbcTemplate).query(
                byMd5.capture(), any(RowMapper.class), eq(TENANT_ID.toString()), eq(USER_ID.toString()), eq("0123456789abcdef0123456789abcdef"));
        assertThat(byMd5.getValue()).doesNotContain("deleted_at IS NULL");
    }

    @Test
    void systemScansAreCrossTenantAndSelectTheOwningTenant() {
        repository.findUnconfirmedDeletions(CURSOR_AT, 50);
        repository.findStaleReindex(CURSOR_AT, 50);
        repository.findStaleParsing(CURSOR_AT, 50);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(3)).query(
                sqlCaptor.capture(), any(RowMapper.class), eq(Timestamp.from(CURSOR_AT)), eq(50));
        List<String> sqls = sqlCaptor.getAllValues();
        assertThat(sqls.get(0))
                .contains("SELECT tenant_id,")
                .contains("deleted_at IS NOT NULL")
                .contains("AND deleted_at < ?")
                .contains("AND (vector_confirmed_at IS NULL OR vector_confirmed_at < deleted_at)")
                .doesNotContain("tenant_id = ?");
        assertThat(sqls.get(1))
                .contains("SELECT tenant_id,")
                .contains("WHERE pending_index_version IS NOT NULL")
                .doesNotContain("status = 'PARSING'")
                .doesNotContain("tenant_id = ?");
        assertThat(sqls.get(2))
                .contains("SELECT tenant_id,")
                .contains("WHERE status = 'PARSING'")
                .doesNotContain("tenant_id = ?");
    }

    @Test
    void tenantDocumentLookupReadsTheOwningTenantWithoutATenantPredicate() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        repository.findTenantDocumentById(DOCUMENT_ID);

        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(DOCUMENT_ID.toString()));
        assertThat(sqlCaptor.getValue())
                .contains("SELECT tenant_id,")
                .contains("FROM document WHERE id = ?")
                .doesNotContain("tenant_id = ?");
    }

    @Test
    void insertBindsTheFullColumnListIncludingTheNewS2Columns() {
        DocumentRow document = new DocumentRow(
                DOCUMENT_ID,
                USER_ID,
                "calculus-notes.pdf",
                "高等数学·第三章笔记",
                "application/pdf",
                10_485_760L,
                "0123456789abcdef0123456789abcdef",
                "documents/" + USER_ID + "/" + DOCUMENT_ID + "/source",
                DocumentStatus.UPLOADED,
                0,
                3,
                null,
                null,
                "v2",
                "v3",
                42L,
                CURSOR_AT,
                null,
                CURSOR_AT,
                NOW,
                CURSOR_AT,
                CURSOR_AT.plusSeconds(60));

        repository.insert(TENANT_ID, document);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                eq(TENANT_ID.toString()),
                eq(DOCUMENT_ID.toString()),
                eq(USER_ID.toString()),
                eq("calculus-notes.pdf"),
                eq("高等数学·第三章笔记"),
                eq("application/pdf"),
                eq(10_485_760L),
                eq("0123456789abcdef0123456789abcdef"),
                eq("documents/" + USER_ID + "/" + DOCUMENT_ID + "/source"),
                eq("UPLOADED"),
                eq(0),
                eq(3),
                isNull(),
                isNull(),
                eq("v2"),
                eq("v3"),
                eq(42L),
                eq(Timestamp.from(CURSOR_AT)),
                isNull(),
                eq(Timestamp.from(CURSOR_AT)),
                eq(Timestamp.from(NOW)),
                eq(Timestamp.from(CURSOR_AT)),
                eq(Timestamp.from(CURSOR_AT.plusSeconds(60))));
        assertThat(sqlCaptor.getValue())
                .contains("pending_index_version, vector_rows, vector_confirmed_at")
                .contains("parsing_started_at, indexed_at, deleted_at, created_at, updated_at");
    }

    @Test
    void mapsEveryListedColumnIncludingTheCursorSortKey() throws Exception {
        repository.findUserPage(TENANT_ID, USER_ID, null, null, null, null, null, 21);

        ArgumentCaptor<RowMapper<DocumentRow>> mapperCaptor = mapperCaptor();
        verify(jdbcTemplate).query(anyString(), mapperCaptor.capture(), eq(TENANT_ID.toString()), eq(USER_ID.toString()), eq(21));
        DocumentRow row = mapperCaptor.getValue().mapRow(resultSet(false), 0);

        assertThat(row.id()).isEqualTo(DOCUMENT_ID);
        assertThat(row.userId()).isEqualTo(USER_ID);
        assertThat(row.fileName()).isEqualTo("calculus-notes.pdf");
        assertThat(row.title()).isEqualTo("高等数学·第三章笔记");
        assertThat(row.status()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(row.indexVersion()).isEqualTo("v2");
        assertThat(row.pendingIndexVersion()).isEqualTo("v3");
        assertThat(row.vectorRows()).isEqualTo(42L);
        assertThat(row.vectorConfirmedAt()).isEqualTo(CURSOR_AT);
        assertThat(row.deleted()).isTrue();
        assertThat(row.rebuilding()).isTrue();
        assertThat(row.createdAt()).isEqualTo(CURSOR_AT);
        assertThat(row.errorMessage()).isNull();
        assertThat(VectorConsistency.of(42L, row.vectorRows())).isEqualTo(VectorConsistency.MATCHED);
    }

    @Test
    void nullVectorRowsStayNullInsteadOfBecomingZero() throws Exception {
        repository.findUserPage(TENANT_ID, USER_ID, null, null, null, null, null, 21);

        ArgumentCaptor<RowMapper<DocumentRow>> mapperCaptor = mapperCaptor();
        verify(jdbcTemplate).query(anyString(), mapperCaptor.capture(), eq(TENANT_ID.toString()), eq(USER_ID.toString()), eq(21));
        DocumentRow row = mapperCaptor.getValue().mapRow(resultSet(true), 0);

        assertThat(row.vectorRows()).isNull();
        assertThat(row.rebuilding()).isFalse();
        assertThat(row.deleted()).isFalse();
        assertThat(VectorConsistency.of(42L, row.vectorRows())).isEqualTo(VectorConsistency.UNKNOWN);
    }

    private static ResultSet resultSet(boolean vectorRowsMissing) {
        try {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("id")).thenReturn(DOCUMENT_ID.toString());
            when(resultSet.getString("user_id")).thenReturn(USER_ID.toString());
            when(resultSet.getString("file_name")).thenReturn("calculus-notes.pdf");
            when(resultSet.getString("title")).thenReturn("高等数学·第三章笔记");
            when(resultSet.getString("content_type")).thenReturn("application/pdf");
            when(resultSet.getLong("size_bytes")).thenReturn(10_485_760L);
            when(resultSet.getString("md5")).thenReturn("0123456789abcdef0123456789abcdef");
            when(resultSet.getString("object_key")).thenReturn("documents/" + USER_ID + "/" + DOCUMENT_ID + "/source");
            when(resultSet.getString("status")).thenReturn("INDEXED");
            when(resultSet.getInt("retry_count")).thenReturn(0);
            when(resultSet.getInt("max_retry")).thenReturn(3);
            when(resultSet.getString("error_code")).thenReturn(null);
            when(resultSet.getString("error_message")).thenReturn(null);
            when(resultSet.getString("index_version")).thenReturn("v2");
            when(resultSet.getString("pending_index_version")).thenReturn(vectorRowsMissing ? null : "v3");
            when(resultSet.getLong("vector_rows")).thenReturn(vectorRowsMissing ? 0L : 42L);
            when(resultSet.wasNull()).thenReturn(vectorRowsMissing);
            when(resultSet.getTimestamp("vector_confirmed_at"))
                    .thenReturn(vectorRowsMissing ? null : Timestamp.from(CURSOR_AT));
            when(resultSet.getTimestamp("parsing_started_at")).thenReturn(Timestamp.from(CURSOR_AT));
            when(resultSet.getTimestamp("indexed_at")).thenReturn(Timestamp.from(CURSOR_AT.plusSeconds(60)));
            when(resultSet.getTimestamp("deleted_at"))
                    .thenReturn(vectorRowsMissing ? null : Timestamp.from(NOW));
            when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(CURSOR_AT));
            when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.from(CURSOR_AT.plusSeconds(60)));
            return resultSet;
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<RowMapper<T>> mapperCaptor() {
        return ArgumentCaptor.forClass(RowMapper.class);
    }
}
