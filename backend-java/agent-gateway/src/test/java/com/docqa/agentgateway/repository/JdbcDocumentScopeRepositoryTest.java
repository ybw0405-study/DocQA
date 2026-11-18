package com.docqa.agentgateway.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.domain.DocumentRecord;
import com.docqa.common.tenant.TenantContext;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcDocumentScopeRepositoryTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SECOND_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcDocumentScopeRepository repository = new JdbcDocumentScopeRepository(jdbcTemplate);

    @Test
    void softDeletedDocumentsAreOutsideTheScope() {
        repository.findByUserAndIds(TENANT_ID, USER_ID, List.of(FIRST_ID, SECOND_ID));

        String sql = capturedSql();
        assertThat(sql).contains("FROM document");
        assertThat(sql).contains("WHERE tenant_id = ? AND deleted_at IS NULL AND user_id = ?");
        assertThat(sql).contains("id IN (?, ?)");
    }

    @Test
    void everyIdentifierTravelsAsABoundParameter() {
        repository.findByUserAndIds(TENANT_ID, USER_ID, List.of(FIRST_ID, SECOND_ID));

        String sql = capturedSql();
        assertThat(sql).doesNotContain(TENANT_ID.toString()).doesNotContain(USER_ID.toString())
                .doesNotContain(FIRST_ID.toString());
        assertThat(sql.substring(0, sql.indexOf("FROM document"))).doesNotContain("*");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsOnlyTheColumnsTheScopeCheckNeeds() throws Exception {
        repository.findByUserAndIds(TENANT_ID, USER_ID, List.of(FIRST_ID));
        ArgumentCaptor<RowMapper<DocumentRecord>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbcTemplate)
                .query(any(String.class), mapperCaptor.capture(), eq(TENANT_ID.toString()),
                        eq(USER_ID.toString()), eq(FIRST_ID.toString()));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(FIRST_ID.toString());
        when(rs.getString("status")).thenReturn("INDEXED");
        when(rs.getString("index_version")).thenReturn("v2");

        assertThat(mapperCaptor.getValue().mapRow(rs, 0)).isEqualTo(new DocumentRecord(FIRST_ID, "INDEXED", "v2"));
    }

    @SuppressWarnings("unchecked")
    private String capturedSql() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        return sqlCaptor.getValue();
    }
}
