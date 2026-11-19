package com.docqa.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 自助注册请求。格式问题返回 400，口令强度问题由 PasswordPolicy 返回 422，两类错误刻意分开。
 * 用户名允许大写输入，服务端统一小写归一后按大小写不敏感判重。
 * tenantSlug 可选：决定新账号归属哪个租户，缺省为默认租户。
 */
public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_.-]{3,32}$")
        String username,

        @NotBlank
        String password,

        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")
        @Size(max = 64) String tenantSlug) {

    /** 兼容 P0 的两参调用（测试与旧客户端）：不指定租户即默认租户。 */
    public RegisterRequest(String username, String password) {
        this(username, password, null);
    }
}
