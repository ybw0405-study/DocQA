package com.docqa.user.controller;

import com.docqa.common.api.ApiResponse;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import com.docqa.user.dto.UserListData;
import com.docqa.user.dto.UserRolesUpdateRequest;
import com.docqa.user.dto.UserStatusUpdateRequest;
import com.docqa.user.dto.UserSummaryData;
import com.docqa.user.service.AccessTokenAuthenticator;
import com.docqa.user.service.AdminUserService;
import com.docqa.user.service.JwtIdentity;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理面。调用者身份只来自已验签令牌，路径里的 userId 是「对谁操作」而不是「我是谁」。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final AdminUserService adminUserService;
    private final AccessTokenAuthenticator accessTokenAuthenticator;

    public UserController(AdminUserService adminUserService, AccessTokenAuthenticator accessTokenAuthenticator) {
        this.adminUserService = adminUserService;
        this.accessTokenAuthenticator = accessTokenAuthenticator;
    }

    @GetMapping
    public ApiResponse<UserListData> list(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "status", required = false) UserStatus status,
            @RequestParam(name = "role", required = false) UserRole role,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        JwtIdentity caller = requireAdmin(authorization);
        return ApiResponse.ok(adminUserService.list(caller.tenantId(), status, role, cursor, limit));
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserSummaryData> get(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable UUID userId) {
        JwtIdentity caller = requireAdmin(authorization);
        return ApiResponse.ok(adminUserService.get(caller.tenantId(), userId));
    }

    @PutMapping("/{userId}/roles")
    public ApiResponse<UserSummaryData> replaceRoles(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable UUID userId,
            @Valid @RequestBody UserRolesUpdateRequest request) {
        JwtIdentity caller = requireAdmin(authorization);
        return ApiResponse.ok(adminUserService.replaceRoles(
                caller.tenantId(), caller.userId(), userId, idempotencyKey, request.roles()));
    }

    @PatchMapping("/{userId}/status")
    public ApiResponse<UserSummaryData> updateStatus(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable UUID userId,
            @Valid @RequestBody UserStatusUpdateRequest request) {
        JwtIdentity caller = requireAdmin(authorization);
        return ApiResponse.ok(adminUserService.updateStatus(
                caller.tenantId(), caller.userId(), userId, idempotencyKey, request.status()));
    }

    private JwtIdentity requireAdmin(String authorization) {
        JwtIdentity identity = accessTokenAuthenticator.authenticate(authorization);
        if (!identity.hasRole(UserRole.ADMIN)) {
            throw new BusinessException(ErrorCode.USR_FORBIDDEN_001);
        }
        return identity;
    }
}
