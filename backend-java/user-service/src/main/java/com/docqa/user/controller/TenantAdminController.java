package com.docqa.user.controller;

import com.docqa.common.api.ApiResponse;
import com.docqa.user.dto.CreateTenantRequest;
import com.docqa.user.dto.TenantCreatedData;
import com.docqa.user.service.TenantProvisioningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户供给端点（P1）。不受 JWT 守卫——引导期还没有任何租户存在；
 * 由独立共享令牌保护，令牌未配置时 service 层一律 403。
 *
 * <p>刻意不放进 AuthController：登录/注册是「租户内身份」，供给是「创建租户本身」，
 * 两者的鉴权模型不同，混在一起容易让人误以为普通用户可以自助建租户。
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class TenantAdminController {

    public static final String TOKEN_HEADER = "X-Bootstrap-Token";

    private final TenantProvisioningService provisioningService;

    public TenantAdminController(TenantProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TenantCreatedData>> create(
            @RequestHeader(value = TOKEN_HEADER, required = false) String bootstrapToken,
            @Valid @RequestBody CreateTenantRequest request) {
        TenantCreatedData created = provisioningService.create(bootstrapToken, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }
}
