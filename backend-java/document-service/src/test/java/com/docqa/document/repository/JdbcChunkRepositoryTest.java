package com.docqa.document.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcChunkRepositoryTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SECOND_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcChunkRepository repository = new JdbcChunkRepository(jdbcTemplate);

    @Test
    void emptyIdListNeverReachesTheDatabase() {
        assertThat(repository.countByDocuments(TENANT_ID, List.of())).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void countsEveryDocumentWithOneGroupedQuery() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.<Map.Entry<UUID, Long>>of(Map.entry(FIRST_ID, 3L), Map.entry(SECOND_ID, 7L)));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        Map<UUID, Long> counts = repository.countByDocuments(TENANT_ID, List.of(FIRST_ID, SECOND_ID));

        assertThat(counts).containsEntry(FIRST_ID, 3L).containsEntry(SECOND_ID, 7L);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(), any(), any());
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("WHERE c.tenant_id = ? AND c.document_id IN (?, ?)");
        assertThat(sql).contains("GROUP BY c.document_id");
        assertThat(sql).doesNotContain(FIRST_ID.toString());
        assertThat(sql).doesNotContain(SECOND_ID.toString());
    }

    @Test
    void countsOnlyChunksOfTheCurrentlyIndexedDocumentVersion() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.<Map.Entry<UUID, Long>>of());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        repository.countByDocuments(TENANT_ID, List.of(FIRST_ID, SECOND_ID));

        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(), any(), any());
        assertThat(sqlCaptor.getValue())
                .contains("JOIN document d ON d.id = c.document_id AND d.tenant_id = c.tenant_id");
    }

    @Test
    void mapsTheCountedByDocumentId() throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.<Map.Entry<UUID, Long>>of());

        ArgumentCaptor<RowMapper<Map.Entry<UUID, Long>>> mapperCaptor = mapperCaptor();
        repository.countByDocuments(TENANT_ID, List.of(FIRST_ID, SECOND_ID));
        verify(jdbcTemplate).query(anyString(), mapperCaptor.capture(), any(), any(), any());

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("document_id")).thenReturn(FIRST_ID.toString());
        when(resultSet.getLong("chunk_count")).thenReturn(42L);
        Map.Entry<UUID, Long> entry = mapperCaptor.getValue().mapRow(resultSet, 0);

        assertThat(entry.getKey()).isEqualTo(FIRST_ID);
        assertThat(entry.getValue()).isEqualTo(42L);
    }

    @Test
    void deletingEveryChunkOfADocumentBindsOnlyTheDocumentKey() {
        repository.deleteByDocument(TENANT_ID, FIRST_ID);

        verify(jdbcTemplate).update(
                "DELETE FROM chunk WHERE tenant_id = ? AND document_id = ?",
                TENANT_ID.toString(), FIRST_ID.toString());
    }

    @Test
    void cleaningASupersededRebuildVersionIsScopedToThatVersionAlone() {
        repository.deleteByVersion(TENANT_ID, FIRST_ID, "v1");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
                sqlCaptor.capture(), eq(TENANT_ID.toString()), eq(FIRST_ID.toString()), eq("v1"));
        assertThat(sqlCaptor.getValue())
                .contains("document_id = ?")
                .contains("AND index_version = ?")
                .doesNotContain(FIRST_ID.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<RowMapper<T>> mapperCaptor() {
        return ArgumentCaptor.forClass(RowMapper.class);
    }
}
