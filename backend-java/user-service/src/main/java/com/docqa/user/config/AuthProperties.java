package com.docqa.user.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "docqa.auth")
public class AuthProperties {

    @Min(1)
    private int maxLoginFailures = 5;

    @NotNull
    private Duration loginFailureWindow = Duration.ofSeconds(60);

    @Min(10)
    @Max(31)
    private int bcryptStrength = 12;

    @NotNull
    private Duration refreshTtl = Duration.ofDays(14);

    @NotNull
    private Duration idempotencyInProgressTtl = Duration.ofSeconds(60);

    @NotNull
    private Duration idempotencyResultTtl = Duration.ofHours(24);

    /** 关闭后自助注册返回 403，建号只能通过管理面。 */
    private boolean selfRegistrationEnabled = true;

    @Min(1)
    private int maxRegistrationsPerWindow = 5;

    @NotNull
    private Duration registrationWindow = Duration.ofSeconds(120);

    @NotBlank
    private String refreshCookieName = "docqa_refresh";

    @NotBlank
    private String refreshCookiePath = "/api/v1/auth";

    /** 本地演示走 http 时置为 false；生产必须 true，否则刷新令牌在链路上明文可见。 */
    private boolean cookieSecure = true;

    @NotBlank
    private String cookieSameSite = "Lax";

    public int getMaxLoginFailures() {
        return maxLoginFailures;
    }

    public void setMaxLoginFailures(int maxLoginFailures) {
        this.maxLoginFailures = maxLoginFailures;
    }

    public Duration getLoginFailureWindow() {
        return loginFailureWindow;
    }

    public void setLoginFailureWindow(Duration loginFailureWindow) {
        this.loginFailureWindow = loginFailureWindow;
    }

    public int getBcryptStrength() {
        return bcryptStrength;
    }

    public void setBcryptStrength(int bcryptStrength) {
        this.bcryptStrength = bcryptStrength;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }

    public Duration getIdempotencyInProgressTtl() {
        return idempotencyInProgressTtl;
    }

    public void setIdempotencyInProgressTtl(Duration idempotencyInProgressTtl) {
        this.idempotencyInProgressTtl = idempotencyInProgressTtl;
    }

    public Duration getIdempotencyResultTtl() {
        return idempotencyResultTtl;
    }

    public void setIdempotencyResultTtl(Duration idempotencyResultTtl) {
        this.idempotencyResultTtl = idempotencyResultTtl;
    }

    public boolean isSelfRegistrationEnabled() {
        return selfRegistrationEnabled;
    }

    public void setSelfRegistrationEnabled(boolean selfRegistrationEnabled) {
        this.selfRegistrationEnabled = selfRegistrationEnabled;
    }

    public int getMaxRegistrationsPerWindow() {
        return maxRegistrationsPerWindow;
    }

    public void setMaxRegistrationsPerWindow(int maxRegistrationsPerWindow) {
        this.maxRegistrationsPerWindow = maxRegistrationsPerWindow;
    }

    public Duration getRegistrationWindow() {
        return registrationWindow;
    }

    public void setRegistrationWindow(Duration registrationWindow) {
        this.registrationWindow = registrationWindow;
    }

    public String getRefreshCookieName() {
        return refreshCookieName;
    }

    public void setRefreshCookieName(String refreshCookieName) {
        this.refreshCookieName = refreshCookieName;
    }

    public String getRefreshCookiePath() {
        return refreshCookiePath;
    }

    public void setRefreshCookiePath(String refreshCookiePath) {
        this.refreshCookiePath = refreshCookiePath;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public String getCookieSameSite() {
        return cookieSameSite;
    }

    public void setCookieSameSite(String cookieSameSite) {
        this.cookieSameSite = cookieSameSite;
    }
}
