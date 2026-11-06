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

public interface UserRepository {

    Optional<UserCredentials> findCredentialsByUsername(String username);

    Optional<UserCredentials> findCredentialsById(UUID id);

    Optional<AppUserRow> findById(UUID id);

    List<AppUserRow> findPage(UserStatus status, UserRole role, Instant beforeCreatedAt, UUID beforeId, int limit);

    boolean existsByUsername(String username);

    /** 账号与其角色在同一个事务里落库：没有角色的账号连令牌都签不出来。 */
    void insert(NewUser user, Set<UserRole> roles);

    int updateStatus(UUID id, UserStatus status, Instant updatedAt);

    int updatePasswordHash(UUID id, String passwordHash, Instant updatedAt);

    int touchLastLogin(UUID id, Instant lastLoginAt);
}
