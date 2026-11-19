package com.docqa.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。tenantSlug 可选：缺省走默认租户（兼容 P0 存量客户端），显式给出时
 * 由 TenantResolver 查库裁决，只在「尚未签发 JWT」的登录入口被信任。
 */
public record LoginRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 72) String password,
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")
        @Size(max = 64) String tenantSlug) {

    /** 兼容 P0 的两参调用（测试与旧客户端）：不指定租户即默认租户。 */
    public LoginRequest(String username, String password) {
        this(username, password, null);
    }
}
