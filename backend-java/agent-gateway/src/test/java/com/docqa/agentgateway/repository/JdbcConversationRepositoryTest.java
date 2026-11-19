package com.docqa.agentgateway.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.dto.ConversationListRow;
import com.docqa.common.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcConversationRepositoryTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CURSOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ROW_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant ACTIVITY = Instant.parse("2026-10-20T10:15:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-19T08:00:00Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcConversationRepository repository =
            new JdbcConversationRepository(jdbcTemplate, new ObjectMapper());

    @Test
    @SuppressWarnings("unchecked")
    void firstPageIsScopedToTheUserAndNewestActivityFirst() {
        repository.findUserPage(TENANT_ID, USER_ID, null, null, false, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(), any(RowMapper.class), eq(TENANT_ID.toString()), eq(USER_ID.toString()), eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM conversation");
        assertThat(sql).contains("WHERE tenant_id = ? AND user_id = ?");
        assertThat(sql).contains("ORDER BY (last_message_at IS NULL) ASC, last_message_at DESC, id DESC LIMIT ?");
        assertThat(sql).doesNotContain("last_message_at < ?");
        assertThat(sql).doesNotContain("last_message_at IS NULL AND id < ?");
        assertThat(sql).doesNotContain(USER_ID.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void activityCursorKeepsTheNoMessageRowsBehindTheScheduledOnes() {
        repository.findUserPage(TENANT_ID, USER_ID, ACTIVITY, CURSOR_ID, false, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(TENANT_ID.toString()),
                eq(USER_ID.toString()),
                eq(Timestamp.from(ACTIVITY)),
                eq(Timestamp.from(ACTIVITY)),
                eq(CURSOR_ID.toString()),
                eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("AND ((last_message_at IS NOT NULL AND (last_message_at < ?");
        assertThat(sql).contains("OR (last_message_at = ? AND id < ?))) OR last_message_at IS NULL)");
        assertThat(sql).doesNotContain(CURSOR_ID.toString());
        assertThat(sql).doesNotContain(ACTIVITY.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumingInsideTheNoMessageGroupComparesOnlyTheId() {
        repository.findUserPage(TENANT_ID, USER_ID, null, CURSOR_ID, true, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(), any(RowMapper.class), eq(TENANT_ID.toString()), eq(USER_ID.toString()),
                eq(CURSOR_ID.toString()), eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("AND (last_message_at IS NULL AND id < ?)");
        assertThat(sql).doesNotContain("last_message_at < ?");
        assertThat(sql).doesNotContain("last_message_at = ?");
    }

    @Test
    @SuppressWarnings("unchecked")
    void halfCursorFallsBackToTheFirstPage() {
        repository.findUserPage(TENANT_ID, USER_ID, ACTIVITY, null, false, 21);

        verify(jdbcTemplate).query(any(String.class), any(RowMapper.class), eq(TENANT_ID.toString()),
                eq(USER_ID.toString()), eq(21));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listProjectionSelectsOnlyContractedColumns() {
        repository.findUserPage(TENANT_ID, USER_ID, null, null, false, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(TENANT_ID.toString()), eq(USER_ID.toString()), eq(21));
        String projection = sqlCaptor.getValue().substring(0, sqlCaptor.getValue().indexOf("FROM conversation"));
        assertThat(projection).contains("id", "title", "document_scope_ids", "last_message_at", "created_at");
        assertThat(projection).doesNotContain("*");
        assertThat(projection).doesNotContain("updated_at");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsTheRowAndParsesTheScopeJsonArray() throws Exception {
        ArgumentCaptor<RowMapper<ConversationListRow>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.findUserPage(TENANT_ID, USER_ID, null, null, false, 21);
        verify(jdbcTemplate).query(any(String.class), mapperCaptor.capture(), eq(TENANT_ID.toString()),
                eq(USER_ID.toString()), eq(21));

        ConversationListRow row = mapperCaptor.getValue().mapRow(resultSet(
                "[\"" + DOCUMENT_ID + "\"]", Timestamp.from(ACTIVITY)), 0);

        assertThat(row.id()).isEqualTo(ROW_ID);
        assertThat(row.title()).isEqualTo("线性代数");
        assertThat(row.documentScopeIds()).containsExactly(DOCUMENT_ID);
        assertThat(row.lastMessageAt()).isEqualTo(ACTIVITY);
        assertThat(row.createdAt()).isEqualTo(CREATED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullScopeAndNullActivityBecomeAnEmptyScopeAndNoTimestamp() throws Exception {
        ArgumentCaptor<RowMapper<ConversationListRow>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.findUserPage(TENANT_ID, USER_ID, null, null, false, 21);
        verify(jdbcTemplate).query(any(String.class), mapperCaptor.capture(), eq(TENANT_ID.toString()),
                eq(USER_ID.toString()), eq(21));

        ConversationListRow row = mapperCaptor.getValue().mapRow(resultSet(null, null), 0);

        assertThat(row.documentScopeIds()).isEmpty();
        assertThat(row.lastMessageAt()).isNull();
    }

    private static ResultSet resultSet(String scopeJson, Timestamp lastMessageAt) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(ROW_ID.toString());
        when(rs.getString("title")).thenReturn("线性代数");
        when(rs.getString("document_scope_ids")).thenReturn(scopeJson);
        when(rs.getTimestamp("last_message_at")).thenReturn(lastMessageAt);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(CREATED));
        return rs;
    }
}
