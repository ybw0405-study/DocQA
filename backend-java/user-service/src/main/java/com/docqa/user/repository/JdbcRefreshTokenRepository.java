package com.docqa.user.repository;

import com.docqa.user.domain.RefreshTokenRow;
import com.docqa.user.domain.RefreshTokenStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private static final String COLUMNS = "id, user_id, family_id, token_hash, status, expires_at, created_at";

    private static final RowMapper<RefreshTokenRow> ROW_MAPPER = JdbcRefreshTokenRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public JdbcRefreshTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(RefreshTokenRow token) {
        jdbcTemplate.update("""
                        INSERT INTO refresh_token (
                            id, user_id, family_id, token_hash, status, expires_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                token.id().toString(),
                token.userId().toString(),
                token.familyId().toString(),
                token.tokenHash(),
                token.status().name(),
                Timestamp.from(token.expiresAt()),
                Timestamp.from(token.createdAt()),
                Timestamp.from(token.createdAt()));
    }

    @Override
    public Optional<RefreshTokenRow> findByHash(String tokenHash) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM refresh_token WHERE token_hash = ?",
                        ROW_MAPPER,
                        tokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public int consumeActive(UUID id, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE refresh_token
                        SET status = 'USED', updated_at = ?
                        WHERE id = ? AND status = 'ACTIVE'
                        """,
                Timestamp.from(now),
                id.toString());
    }

    @Override
    public int revokeFamily(UUID familyId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE refresh_token
                        SET status = 'REVOKED', updated_at = ?
                        WHERE family_id = ? AND status = 'ACTIVE'
                        """,
                Timestamp.from(now),
                familyId.toString());
    }

    @Override
    public int revokeAllActiveForUser(UUID userId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE refresh_token
                        SET status = 'REVOKED', updated_at = ?
                        WHERE user_id = ? AND status = 'ACTIVE'
                        """,
                Timestamp.from(now),
                userId.toString());
    }

    @Override
    public int deleteExpiredTerminal(Instant now, int limit) {
        return jdbcTemplate.update("""
                        DELETE FROM refresh_token
                        WHERE status IN ('USED', 'REVOKED') AND expires_at < ?
                        LIMIT ?
                        """,
                Timestamp.from(now),
                limit);
    }

    private static RefreshTokenRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RefreshTokenRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("user_id")),
                UUID.fromString(rs.getString("family_id")),
                rs.getString("token_hash"),
                RefreshTokenStatus.from(rs.getString("status")),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
