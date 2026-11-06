package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.AiQuota;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAiQuotaRepository implements AiQuotaRepository {

    static final String FIND_USERNAME_SQL = "SELECT username FROM app_user WHERE id = ?";

    static final String PROJECTION = """
            SELECT q.user_id, u.username, q.daily_question_limit, q.daily_token_limit,
                   q.updated_at, q.updated_by
            FROM ai_quota q
            JOIN app_user u ON u.id = q.user_id
            """;

    static final String FIND_BY_USER_SQL = PROJECTION + " WHERE q.user_id = ?";

    static final String UPSERT_SQL = """
            INSERT INTO ai_quota (user_id, daily_question_limit, daily_token_limit, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              daily_question_limit = VALUES(daily_question_limit),
              daily_token_limit = VALUES(daily_token_limit),
              updated_at = VALUES(updated_at),
              updated_by = VALUES(updated_by)
            """;

    static final String DELETE_SQL = "DELETE FROM ai_quota WHERE user_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AiQuota> rowMapper = this::mapRow;

    public JdbcAiQuotaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<String> findUsername(UUID userId) {
        List<String> usernames = jdbcTemplate.query(
                FIND_USERNAME_SQL, (rs, rowNum) -> rs.getString("username"), userId.toString());
        return usernames.stream().findFirst();
    }

    @Override
    public Optional<AiQuota> findByUserId(UUID userId) {
        List<AiQuota> rows = jdbcTemplate.query(FIND_BY_USER_SQL, rowMapper, userId.toString());
        return rows.stream().findFirst();
    }

    @Override
    public List<AiQuota> findPage(Instant beforeUpdatedAt, UUID beforeUserId, int limit) {
        if (beforeUpdatedAt != null && beforeUserId != null) {
            Timestamp cursorUpdatedAt = Timestamp.from(beforeUpdatedAt);
            return jdbcTemplate.query(
                    PROJECTION
                            + " WHERE (q.updated_at < ? OR (q.updated_at = ? AND q.user_id < ?))"
                            + " ORDER BY q.updated_at DESC, q.user_id DESC LIMIT ?",
                    rowMapper,
                    cursorUpdatedAt,
                    cursorUpdatedAt,
                    beforeUserId.toString(),
                    limit);
        }
        return jdbcTemplate.query(
                PROJECTION + " ORDER BY q.updated_at DESC, q.user_id DESC LIMIT ?",
                rowMapper,
                limit);
    }

    @Override
    public void upsert(UUID userId, Long dailyQuestionLimit, Long dailyTokenLimit, UUID updatedBy, Instant now) {
        jdbcTemplate.update(
                UPSERT_SQL,
                userId.toString(),
                dailyQuestionLimit,
                dailyTokenLimit,
                Timestamp.from(now),
                updatedBy == null ? null : updatedBy.toString());
    }

    @Override
    public void delete(UUID userId) {
        jdbcTemplate.update(DELETE_SQL, userId.toString());
    }

    private AiQuota mapRow(ResultSet rs, int rowNum) throws SQLException {
        String updatedBy = rs.getString("updated_by");
        return new AiQuota(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("username"),
                nullableLong(rs, "daily_question_limit"),
                nullableLong(rs, "daily_token_limit"),
                toInstant(rs.getTimestamp("updated_at")),
                updatedBy == null ? null : UUID.fromString(updatedBy));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
