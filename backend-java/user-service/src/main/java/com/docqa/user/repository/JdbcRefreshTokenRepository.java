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
    public void insert(UUID tenantId, RefreshTokenRow token) {
        jdbcTemplate.update("""
                        INSERT INTO refresh_token (
                            tenant_id, id, user_id, family_id, token_hash, status, expires_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                tenantId.toString(),
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
    public Optional<RefreshTokenRow> findByHash(UUID tenantId, String tokenHash) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM refresh_token WHERE tenant_id = ? AND token_hash = ?",
                        ROW_MAPPER,
                        tenantId.toString(), tokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<UUID> findTenantIdByHash(String tokenHash) {
        // 控制面：凭据本身即作用域，见 RefreshTokenRepository#findTenantIdByHash 的约定。
        return jdbcTemplate.query(
                        "SELECT tenant_id FROM refresh_token WHERE token_hash = ?",
                        (rs, rowNum) -> UUID.fromString(rs.getString("tenant_id")),
                        tokenHash)
                .stream()
                .findFirst();
    }

    @Override
    public int consumeActive(UUID tenantId, UUID id, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE refresh_token
                        SET status = 'USED', updated_at = ?
                        WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                        """,
                Timestamp.from(now), tenantId.toString(), id.toString());
    }

    @Override
    public int revokeFamily(UUID tenantId, UUID familyId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE refresh_token
                        SET status = 'REVOKED', updated_at = ?
                        WHERE tenant_id = ? AND family_id = ? AND status = 'ACTIVE'
                        """,
                Timestamp.from(now), tenantId.toString(), familyId.toString());
    }

    @Override
    public int revokeAllActiveForUser(UUID tenantId, UUID userId, Instant now) {
        return jdbcTemplate.update("""
                        UPDATE refresh_token
                        SET status = 'REVOKED', updated_at = ?
                        WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'
                        """,
                Timestamp.from(now), tenantId.toString(), userId.toString());
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
