package com.docqa.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 租户供给请求。tier / isolation 由 Bean Validation 的 {@code @Pattern} 做白名单裁决，
 * 避免非法枚举直接打到数据库 CHECK 约束触发 500。
 */
public record CreateTenantRequest(
        @NotBlank
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,63}$")
        String slug,

        @NotBlank
        @Size(max = 120)
        String name,

        @NotBlank
        @Pattern(regexp = "^(FREE|STANDARD|PRO|ENTERPRISE)$")
        String tier,

        @NotBlank
        @Pattern(regexp = "^(SHARED|SCHEMA|DEDICATED)$")
        String isolation) {
}
