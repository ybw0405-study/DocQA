package com.docqa.user.config;

import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 租户供给 bootstrap 端点的共享令牌配置。
 *
 * <p>刻意不引入「平台管理员」角色：那会扩展角色枚举、JWT claim 与三服务守卫逻辑，
 * 而当前只是需要一个能创建首个真实租户的引导入口。令牌由运维注入环境变量，
 * 留空则端点整体禁用（默认安全）。
 */
@Validated
@ConfigurationProperties(prefix = "docqa.tenant-bootstrap")
public class TenantBootstrapProperties {

    /** 共享令牌；为空或空白时供给端点禁用。生产须通过环境变量注入高熵值。 */
    @Size(max = 256)
    private String token = "";

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }

    public boolean isEnabled() {
        return token != null && !token.isBlank();
    }
}
