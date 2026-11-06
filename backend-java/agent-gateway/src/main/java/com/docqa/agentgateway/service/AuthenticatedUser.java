package com.docqa.agentgateway.service;

import java.util.Set;
import java.util.UUID;

/** 已验签令牌的身份视图；roles 只保留字符串元素，未知角色忽略。 */
public record AuthenticatedUser(UUID userId, Set<String> roles) {

    public static final String ADMIN = "ADMIN";

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    public boolean isAdmin() {
        return roles.contains(ADMIN);
    }
}
