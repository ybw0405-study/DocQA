package com.docqa.common.web;

import java.util.Set;

/**
 * 网关放行清单：契约中不要求 Bearer 的端点必须逐条登记在这里，不能靠前缀通配。
 * 前缀放行（例如整个 `/api/v1/auth/`）会把同前缀下后来新增的鉴权端点一起放行，
 * 而这里漏登记只会让请求在网关被 401，失败方向是安全的一侧。
 * 该集合与 `openapi-java.yaml` 中「存在免 Bearer 备选」的端点集合由契约测试对账。
 */
public final class PublicPaths {

    private static final Set<String> EXEMPT = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout");

    private PublicPaths() {
    }

    public static boolean isBearerExempt(String path) {
        return path != null && EXEMPT.contains(path);
    }

    public static Set<String> all() {
        return EXEMPT;
    }
}
