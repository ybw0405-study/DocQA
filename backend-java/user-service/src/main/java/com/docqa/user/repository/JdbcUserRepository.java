package com.docqa.user.repository;

import com.docqa.user.domain.AppUserRow;
import com.docqa.user.domain.NewUser;
import com.docqa.user.domain.UserCredentials;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcUserRepository implements UserRepository {

    private static final String PUBLIC_COLUMNS = "id, username, status, last_login_at, created_at";

    private static final String CREDENTIAL_SELECT = "SELECT id, username, password_hash, status FROM app_user WHERE ";

    private static final RowMapper<UserCredentials> CREDENTIAL_MAPPER = (rs, rowNum) -> new UserCredentials(
            UUID.fromString(rs.getString("id")),
            rs.getString("username"),
            rs.getString("password_hash"),
            UserStatus.from(rs.getString("status")));

    private static final RowMapper<AppUserRow> ROW_MAPPER = (rs, rowNum) -> new AppUserRow(
            UUID.fromString(rs.getString("id")),
            rs.getString("username"),
            UserStatus.from(rs.getString("status")),
            toInstant(rs, "last_login_at"),
            toInstant(rs, "created_at"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UserCredentials> findCredentialsByUsername(String username) {
        return jdbcTemplate.query(
                        CREDENTIAL_SELECT + " username = ?",
                        CREDENTIAL_MAPPER,
                        username)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<UserCredentials> findCredentialsById(UUID id) {
        return jdbcTemplate.query(
                        CREDENTIAL_SELECT + " id = ?",
                        CREDENTIAL_MAPPER,
                        id.toString())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<AppUserRow> findById(UUID id) {
        return jdbcTemplate.query(
                        "SELECT " + PUBLIC_COLUMNS + " FROM app_user WHERE id = ?",
                        ROW_MAPPER,
                        id.toString())
                .stream()
                .findFirst();
    }

    @Override
    public List<AppUserRow> findPage(UserStatus status, UserRole role, Instant beforeCreatedAt, UUID beforeId,
            int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(PUBLIC_COLUMNS).append(" FROM app_user u WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" AND u.status = ?");
            args.add(status.name());
        }
        if (role != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM app_user_role r WHERE r.user_id = u.id AND r.role = ?)");
            args.add(role.name());
        }
        if (beforeCreatedAt != null && beforeId != null) {
            sql.append(" AND (u.created_at < ? OR (u.created_at = ? AND u.id < ?))");
            Timestamp cursor = Timestamp.from(beforeCreatedAt);
            args.add(cursor);
            args.add(cursor);
            args.add(beforeId.toString());
        }
        sql.append(" ORDER BY u.created_at DESC, u.id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    @Override
    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public void insert(NewUser user, Set<UserRole> roles) {
        Timestamp createdAt = Timestamp.from(user.createdAt());
        jdbcTemplate.update("""
                        INSERT INTO app_user (id, username, password_hash, status, last_login_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, NULL, ?, ?)
                        """,
                user.id().toString(),
                user.username(),
                user.passwordHash(),
                user.status().name(),
                createdAt,
                createdAt);
        for (UserRole role : roles) {
            jdbcTemplate.update(
                    "INSERT INTO app_user_role (user_id, role, created_at) VALUES (?, ?, ?)",
                    user.id().toString(),
                    role.name(),
                    createdAt);
        }
    }

    @Override
    public int updateStatus(UUID id, UserStatus status, Instant updatedAt) {
        return jdbcTemplate.update(
                "UPDATE app_user SET status = ?, updated_at = ? WHERE id = ?",
                status.name(),
                Timestamp.from(updatedAt),
                id.toString());
    }

    @Override
    public int updatePasswordHash(UUID id, String passwordHash, Instant updatedAt) {
        return jdbcTemplate.update(
                "UPDATE app_user SET password_hash = ?, updated_at = ? WHERE id = ?",
                passwordHash,
                Timestamp.from(updatedAt),
                id.toString());
    }

    @Override
    public int touchLastLogin(UUID id, Instant lastLoginAt) {
        return jdbcTemplate.update(
                "UPDATE app_user SET last_login_at = ? WHERE id = ?",
                Timestamp.from(lastLoginAt),
                id.toString());
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
