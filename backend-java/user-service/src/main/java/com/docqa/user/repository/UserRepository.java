package com.docqa.user.repository;

import com.docqa.user.domain.AppUserRow;
import com.docqa.user.domain.NewUser;
import com.docqa.user.domain.UserCredentials;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 所有查询都要求显式 tenantId：租户依赖出现在方法签名上，由编译器兜底防止漏传。
 * 登录/注册这类尚无 JWT 的路径由调用方显式传 DEFAULT_TENANT_ID（P0 迁移期唯一合法租户）。
 */
public interface UserRepository {

    Optional<UserCredentials> findCredentialsByUsername(UUID tenantId, String username);

    Optional<UserCredentials> findCredentialsById(UUID tenantId, UUID id);

    Optional<AppUserRow> findById(UUID tenantId, UUID id);

    List<AppUserRow> findPage(UUID tenantId, UserStatus status, UserRole role,
            Instant beforeCreatedAt, UUID beforeId, int limit);

    boolean existsByUsername(UUID tenantId, String username);

    /** 账号与其角色在同一个事务里落库：没有角色的账号连令牌都签不出来。 */
    void insert(UUID tenantId, NewUser user, Set<UserRole> roles);

    int updateStatus(UUID tenantId, UUID id, UserStatus status, Instant updatedAt);

    int updatePasswordHash(UUID tenantId, UUID id, String passwordHash, Instant updatedAt);

    int touchLastLogin(UUID tenantId, UUID id, Instant lastLoginAt);
}
