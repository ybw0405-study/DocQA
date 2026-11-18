package com.docqa.user.repository;

import com.docqa.user.domain.UserRole;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRoleRepository implements RoleRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRoleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Set<UserRole> findRoles(UUID tenantId, UUID userId) {
        List<UserRole> roles = jdbcTemplate.query(
                "SELECT role FROM app_user_role WHERE tenant_id = ? AND user_id = ? ORDER BY role",
                (ResultSet rs, int rowNum) -> UserRole.from(rs.getString("role")),
                tenantId.toString(), userId.toString());
        if (roles.isEmpty()) {
            return Set.of();
        }
        return EnumSet.copyOf(roles);
    }

    @Override
    public Map<UUID, Set<UserRole>> findRolesByUserIds(UUID tenantId, Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = userIds.stream().map(UUID::toString).toList();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(", "));
        Map<UUID, Set<UserRole>> result = new HashMap<>();
        jdbcTemplate.query(
                "SELECT user_id, role FROM app_user_role WHERE tenant_id = ? AND user_id IN ("
                        + placeholders + ") ORDER BY user_id, role",
                (ResultSet rs) -> {
                    UUID userId = UUID.fromString(rs.getString("user_id"));
                    result.computeIfAbsent(userId, key -> EnumSet.noneOf(UserRole.class))
                            .add(UserRole.from(rs.getString("role")));
                },
                buildArgs(tenantId, ids));
        return Map.copyOf(result);
    }

    @Override
    @Transactional
    public void replaceRoles(UUID tenantId, UUID userId, Set<UserRole> roles, Instant createdAt) {
        jdbcTemplate.update(
                "DELETE FROM app_user_role WHERE tenant_id = ? AND user_id = ?",
                tenantId.toString(), userId.toString());
        // 整集替换必须是「删干净再插」的单个原子动作，否则中途失败会留下半套权限。
        Set<UserRole> ordered = new LinkedHashSet<>(roles);
        Timestamp created = Timestamp.from(createdAt);
        for (UserRole role : ordered) {
            jdbcTemplate.update(
                    "INSERT INTO app_user_role (tenant_id, user_id, role, created_at) VALUES (?, ?, ?, ?)",
                    tenantId.toString(), userId.toString(), role.name(), created);
        }
    }

    private static Object[] buildArgs(UUID tenantId, List<String> userIds) {
        Object[] args = new Object[userIds.size() + 1];
        args[0] = tenantId.toString();
        for (int i = 0; i < userIds.size(); i++) {
            args[i + 1] = userIds.get(i);
        }
        return args;
    }
}
