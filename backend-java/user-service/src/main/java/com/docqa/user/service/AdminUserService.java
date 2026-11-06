package com.docqa.user.service;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.domain.AppUserRow;
import com.docqa.user.domain.IdempotencyOperation;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import com.docqa.user.dto.UserListData;
import com.docqa.user.dto.UserSummaryData;
import com.docqa.user.repository.RoleRepository;
import com.docqa.user.repository.UserRepository;
import com.docqa.user.support.Fingerprints;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 管理面用户操作。每个写操作都是「读-判定-写」，调用者的身份只由 Controller 传入的令牌视图决定。 */
@Service
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int IDEMPOTENCY_KEY_MIN_LENGTH = 8;
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenService refreshTokenService;
    private final AccessTokenAuthenticator accessTokenAuthenticator;
    private final IdempotencyGuard idempotencyGuard;
    private final Clock clock;

    public AdminUserService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenService refreshTokenService,
            AccessTokenAuthenticator accessTokenAuthenticator,
            IdempotencyGuard idempotencyGuard,
            Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenAuthenticator = accessTokenAuthenticator;
        this.idempotencyGuard = idempotencyGuard;
        this.clock = clock;
    }

    public UserListData list(UserStatus status, UserRole role, String cursor, int limit) {
        int pageSize = validatePageSize(limit);
        KeysetCursor keyset = parseCursor(cursor);

        List<AppUserRow> rows = userRepository.findPage(
                status,
                role,
                keyset == null ? null : keyset.sortKey(),
                keyset == null ? null : keyset.id(),
                pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<AppUserRow> page = hasMore ? List.copyOf(rows.subList(0, pageSize)) : rows;

        Map<UUID, Set<UserRole>> rolesByUser = roleRepository.findRolesByUserIds(
                page.stream().map(AppUserRow::id).toList());
        List<UserSummaryData> items = page.stream()
                .map(row -> toSummary(row, rolesByUser.getOrDefault(row.id(), Set.of())))
                .toList();

        String nextCursor = null;
        if (hasMore) {
            AppUserRow last = page.get(page.size() - 1);
            nextCursor = KeysetCursor.of(last.createdAt(), last.id()).encode();
        }
        return new UserListData(items, nextCursor, pageSize);
    }

    public UserSummaryData get(UUID userId) {
        return toSummary(requireUser(userId), roleRepository.findRoles(userId));
    }

    public UserSummaryData replaceRoles(
            UUID callerId,
            UUID targetId,
            String idempotencyKey,
            Set<UserRole> roles) {
        String key = requireIdempotencyKey(idempotencyKey);
        return idempotencyGuard.run(
                        callerId.toString(),
                        IdempotencyOperation.USER_ROLE_UPDATE,
                        key,
                        rolesFingerprint(roles),
                        UserSummaryData.class,
                        () -> {
                            rejectSelfLockout(callerId, targetId, roles);
                            requireUser(targetId);
                            roleRepository.replaceRoles(targetId, roles, clock.instant());
                            return get(targetId);
                        })
                .response();
    }

    public UserSummaryData updateStatus(
            UUID callerId,
            UUID targetId,
            String idempotencyKey,
            UserStatus status) {
        String key = requireIdempotencyKey(idempotencyKey);
        String fingerprint = Fingerprints.sha256(
                IdempotencyOperation.USER_STATUS_UPDATE.name() + '\u0000' + status);
        return idempotencyGuard.run(
                        callerId.toString(),
                        IdempotencyOperation.USER_STATUS_UPDATE,
                        key,
                        fingerprint,
                        UserSummaryData.class,
                        () -> {
                            if (targetId.equals(callerId) && status == UserStatus.DISABLED) {
                                throw policyError("status", "cannot disable the calling administrator");
                            }
                            requireUser(targetId);
                            Instant now = clock.instant();
                            userRepository.updateStatus(targetId, status, now);
                            if (status == UserStatus.DISABLED) {
                                // 停用必须即时生效：只改状态列等于给被盗账号留 15 分钟可用窗口。
                                refreshTokenService.revokeAllForUser(targetId);
                                accessTokenAuthenticator.revokeAllIssuedBefore(targetId, now);
                            }
                            return get(targetId);
                        })
                .response();
    }

    private void rejectSelfLockout(UUID callerId, UUID targetId, Set<UserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            throw policyError("roles", "at least one role is required");
        }
        if (targetId.equals(callerId) && !roles.contains(UserRole.ADMIN)) {
            throw policyError("roles", "cannot remove ADMIN from the calling administrator");
        }
    }

    private AppUserRow requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USR_NOT_FOUND_001));
    }

    private UserSummaryData toSummary(AppUserRow row, Set<UserRole> roles) {
        return new UserSummaryData(
                row.id(),
                row.username(),
                row.status(),
                roles,
                row.createdAt(),
                row.lastLoginAt());
    }

    private int validatePageSize(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("limit", "must be between 1 and " + MAX_PAGE_SIZE)));
        }
        return limit;
    }

    private KeysetCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        return KeysetCursor.decode(cursor)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.COM_VALIDATION_001,
                        null,
                        List.of(FieldViolation.of("cursor", "cursor is malformed or belongs to another endpoint"))));
    }

    private String rolesFingerprint(Set<UserRole> roles) {
        String normalized = roles == null
                ? ""
                : roles.stream().map(UserRole::name).sorted().collect(Collectors.joining(","));
        return Fingerprints.sha256(IdempotencyOperation.USER_ROLE_UPDATE.name() + '\u0000' + normalized);
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() < IDEMPOTENCY_KEY_MIN_LENGTH
                || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("Idempotency-Key", "must be 8-64 non-blank characters")));
        }
        return idempotencyKey;
    }

    private BusinessException policyError(String field, String message) {
        return new BusinessException(
                ErrorCode.USR_VALIDATION_001,
                null,
                List.of(FieldViolation.of(field, message)));
    }
}
