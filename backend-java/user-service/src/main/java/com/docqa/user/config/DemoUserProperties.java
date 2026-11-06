package com.docqa.user.config;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 首次启动种子账号配置；只在 app_user 为空时写入一次，之后登录一律走数据库。 */
@Validated
@ConfigurationProperties(prefix = "docqa.demo-user")
public class DemoUserProperties {

    private String username = "demo";

    private String passwordHash = "";

    @NotNull
    private UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** 种子账号同时持有 ADMIN，便于本地演示管理面；生产部署应置为 false。 */
    private boolean admin = true;

    /** 关掉后不再建种子账号，用于已接入真实注册流程的部署。 */
    private boolean enabled = true;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
