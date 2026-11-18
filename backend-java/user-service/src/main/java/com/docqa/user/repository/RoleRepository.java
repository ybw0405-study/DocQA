package com.docqa.user.repository;

import com.docqa.user.domain.UserRole;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface RoleRepository {

    Set<UserRole> findRoles(UUID tenantId, UUID userId);

    /** 列表页按整页 id 批量取角色，避免逐行 N+1 查询。 */
    Map<UUID, Set<UserRole>> findRolesByUserIds(UUID tenantId, Collection<UUID> userIds);

    void replaceRoles(UUID tenantId, UUID userId, Set<UserRole> roles, Instant createdAt);
}
