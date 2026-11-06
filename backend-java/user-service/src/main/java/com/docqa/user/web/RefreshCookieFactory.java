package com.docqa.user.web;

import com.docqa.user.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 刷新令牌只在 HttpOnly Cookie 里流转：脚本读不到原文，前端也就不需要保存它。
 * Path 收窄到 /api/v1/auth，避免刷新凭据随每个业务请求一起发出。
 */
@Component
public class RefreshCookieFactory {

    private final AuthProperties properties;

    public RefreshCookieFactory(AuthProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie issue(String value) {
        return build(value, properties.getRefreshTtl().toSeconds());
    }

    public ResponseCookie clear() {
        return build("", 0);
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = properties.getRefreshCookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private ResponseCookie build(String value, long maxAgeSeconds) {
        return ResponseCookie.from(properties.getRefreshCookieName(), value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite(properties.getCookieSameSite())
                .path(properties.getRefreshCookiePath())
                .maxAge(maxAgeSeconds)
                .build();
    }
}
