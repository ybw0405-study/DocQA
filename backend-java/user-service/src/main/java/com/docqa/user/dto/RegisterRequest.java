package com.docqa.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 自助注册请求。格式问题返回 400，口令强度问题由 PasswordPolicy 返回 422，两类错误刻意分开。
 * 用户名允许大写输入，服务端统一小写归一后按大小写不敏感判重。
 */
public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_.-]{3,32}$")
        String username,

        @NotBlank
        String password) {
}
