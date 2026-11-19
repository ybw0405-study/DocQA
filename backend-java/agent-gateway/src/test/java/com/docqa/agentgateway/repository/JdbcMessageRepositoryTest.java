package com.docqa.agentgateway.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.docqa.common.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcMessageRepositoryTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CURSOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant CREATED_AT = Instant.parse("2026-10-20T10:15:00Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcMessageRepository repository = new JdbcMessageRepository(jdbcTemplate);

    @Test
    void firstHistoryPageIsScopedToTheConversationAndNewestFirst() {
        repository.findPage(TENANT_ID, CONVERSATION_ID, null, null, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(), any(RowMapper.class), eq(TENANT_ID.toString()), eq(CONVERSATION_ID.toString()), eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM message");
        assertThat(sql).contains("WHERE tenant_id = ? AND conversation_id = ?");
        assertThat(sql).contains("ORDER BY created_at DESC, id DESC LIMIT ?");
        assertThat(sql).doesNotContain("created_at < ?");
        assertThat(sql).doesNotContain(CONVERSATION_ID.toString());
    }

    @Test
    void historyNeverSelectsColumnsOutsideTheContract() {
        repository.findPage(TENANT_ID, CONVERSATION_ID, null, null, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(TENANT_ID.toString()), eq(CONVERSATION_ID.toString()), eq(21));
        String projection = sqlCaptor.getValue().substring(0, sqlCaptor.getValue().indexOf("FROM message"));
        assertThat(projection).contains("id", "role", "content", "token_count", "trace_id", "created_at");
        assertThat(projection).doesNotContain("*");
        assertThat(projection).doesNotContain("user_id");
        assertThat(projection).doesNotContain("metadata");
    }

    @Test
    void keysetCursorBindsTheSortPairAsPlaceholders() {
        repository.findPage(TENANT_ID, CONVERSATION_ID, CREATED_AT, CURSOR_ID, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(TENANT_ID.toString()), eq(CONVERSATION_ID.toString()),
                eq(Timestamp.from(CREATED_AT)),
                eq(Timestamp.from(CREATED_AT)),
                eq(CURSOR_ID.toString()),
                eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("AND (created_at < ? OR (created_at = ? AND id < ?))");
        assertThat(sql).doesNotContain(CURSOR_ID.toString());
    }

    @Test
    void halfCursorFallsBackToTheFirstPage() {
        repository.findPage(TENANT_ID, CONVERSATION_ID, CREATED_AT, null, 21);

        verify(jdbcTemplate).query(any(String.class), any(RowMapper.class), eq(TENANT_ID.toString()), eq(CONVERSATION_ID.toString()), eq(21));
    }
}
