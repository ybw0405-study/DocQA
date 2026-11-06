package com.docqa.agentgateway.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.dto.AiQuota;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAiQuotaRepositoryTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CURSOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant UPDATED_AT = Instant.parse("2026-11-03T09:20:03.004Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcAiQuotaRepository repository = new JdbcAiQuotaRepository(jdbcTemplate);

    @Test
    void resolvesUsernameFromTheIdentityTable() {
        when(jdbcTemplate.query(
                eq(JdbcAiQuotaRepository.FIND_USERNAME_SQL), any(RowMapper.class), eq(USER_ID.toString())))
                .thenReturn(List.of("zhang.wei"));

        assertThat(repository.findUsername(USER_ID)).contains("zhang.wei");
        verify(jdbcTemplate).query(
                eq(JdbcAiQuotaRepository.FIND_USERNAME_SQL), any(RowMapper.class), eq(USER_ID.toString()));
    }

    @Test
    void reportsAbsentUsersAsEmpty() {
        when(jdbcTemplate.query(
                eq(JdbcAiQuotaRepository.FIND_USERNAME_SQL), any(RowMapper.class), eq(USER_ID.toString())))
                .thenReturn(List.of());

        assertThat(repository.findUsername(USER_ID)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsOneQuotaByPrimaryKeyWithTheUsernameJoin() {
        repository.findByUserId(USER_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(USER_ID.toString()));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM ai_quota q");
        assertThat(sql).contains("JOIN app_user u ON u.id = q.user_id");
        assertThat(sql).contains("WHERE q.user_id = ?");
        assertThat(sql).doesNotContain("*");
        assertThat(sql).doesNotContain(USER_ID.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstPageOrdersByUpdatedAtThenUserId() {
        repository.findPage(null, null, 51);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(51));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("ORDER BY q.updated_at DESC, q.user_id DESC LIMIT ?");
        assertThat(sql).doesNotContain("q.updated_at < ?");
        assertThat(sql).doesNotContain("q.user_id < ?");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cursorKeepsUpdatedAtThenUserIdOrderStrictly() {
        repository.findPage(UPDATED_AT, CURSOR_ID, 51);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(Timestamp.from(UPDATED_AT)),
                eq(Timestamp.from(UPDATED_AT)),
                eq(CURSOR_ID.toString()),
                eq(51));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("WHERE (q.updated_at < ? OR (q.updated_at = ? AND q.user_id < ?))");
        assertThat(sql).doesNotContain(CURSOR_ID.toString());
        assertThat(sql).doesNotContain(UPDATED_AT.toString());
    }

    @Test
    void halfCursorFallsBackToTheFirstPage() {
        repository.findPage(null, CURSOR_ID, 51);

        verify(jdbcTemplate).query(any(String.class), any(RowMapper.class), eq(51));
    }

    @Test
    void upsertReplacesBothLimitsAndProvenance() {
        repository.upsert(USER_ID, 30L, 60_000L, ADMIN_ID, UPDATED_AT);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(eq(JdbcAiQuotaRepository.UPSERT_SQL), captor.capture());
        Object[] args = captor.getValue();
        assertThat(args).containsExactly(
                USER_ID.toString(), 30L, 60_000L, Timestamp.from(UPDATED_AT), ADMIN_ID.toString());
        String sql = JdbcAiQuotaRepository.UPSERT_SQL;
        assertThat(sql).startsWith("INSERT INTO ai_quota");
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE");
        assertThat(sql).contains("daily_question_limit = VALUES(daily_question_limit)");
        assertThat(sql).contains("daily_token_limit = VALUES(daily_token_limit)");
        assertThat(sql).contains("updated_at = VALUES(updated_at)");
        assertThat(sql).contains("updated_by = VALUES(updated_by)");
    }

    @Test
    void clearingAQuotaDeletesTheRow() {
        repository.delete(USER_ID);

        verify(jdbcTemplate).update(JdbcAiQuotaRepository.DELETE_SQL, USER_ID.toString());
        assertThat(JdbcAiQuotaRepository.DELETE_SQL).isEqualTo("DELETE FROM ai_quota WHERE user_id = ?");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsNullableLimitsAndProvenance() throws Exception {
        ArgumentCaptor<RowMapper<AiQuota>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.findByUserId(USER_ID);
        verify(jdbcTemplate).query(any(String.class), mapperCaptor.capture(), eq(USER_ID.toString()));

        AiQuota row = mapperCaptor.getValue().mapRow(resultSet(null, null), 0);

        assertThat(row.userId()).isEqualTo(USER_ID);
        assertThat(row.username()).isEqualTo("zhang.wei");
        assertThat(row.dailyQuestionLimit()).isNull();
        assertThat(row.dailyTokenLimit()).isNull();
        assertThat(row.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(row.updatedBy()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsUnsignedLimitsAsLong() throws Exception {
        ArgumentCaptor<RowMapper<AiQuota>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.findByUserId(USER_ID);
        verify(jdbcTemplate).query(any(String.class), mapperCaptor.capture(), eq(USER_ID.toString()));

        ResultSet rs = resultSet(4_294_967_295L, 60_000L);
        AiQuota row = mapperCaptor.getValue().mapRow(rs, 0);

        assertThat(row.dailyQuestionLimit()).isEqualTo(4_294_967_295L);
        assertThat(row.dailyTokenLimit()).isEqualTo(60_000L);
        assertThat(row.updatedBy()).isEqualTo(ADMIN_ID);
    }

    private static ResultSet resultSet(Object questionLimit, Object tokenLimit) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("user_id")).thenReturn(USER_ID.toString());
        when(rs.getString("username")).thenReturn("zhang.wei");
        when(rs.getObject("daily_question_limit")).thenReturn(questionLimit);
        when(rs.getObject("daily_token_limit")).thenReturn(tokenLimit);
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(UPDATED_AT));
        when(rs.getString("updated_by"))
                .thenReturn(questionLimit == null && tokenLimit == null ? null : ADMIN_ID.toString());
        return rs;
    }
}
