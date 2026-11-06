package com.docqa.user.service;

/**
 * 一次会话签发的结果。refreshToken 只在首次签发时回填，幂等重放时为 null——
 * 重放返回的是当初的响应正文，不应再建立一个新的轮换家族。
 */
public record IssuedSession(IssuedToken access, String refreshToken) {

    public boolean issuesRefreshToken() {
        return refreshToken != null;
    }
}
