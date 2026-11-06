package com.docqa.user.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

public enum UserRole {
    USER,
    ADMIN;

    public static UserRole from(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Unknown user role: " + value, ex);
        }
    }

    /** 令牌里的 roles claim 可能是任意集合实现，出现未知角色时整枚令牌按非法处理。 */
    public static Set<UserRole> fromClaim(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException("roles claim must not be empty");
        }
        Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
        for (Object value : values) {
            if (!(value instanceof String name)) {
                throw new IllegalStateException("roles claim must contain strings");
            }
            roles.add(from(name));
        }
        return roles;
    }

    public static String[] claimValues(Collection<UserRole> roles) {
        return roles.stream().map(UserRole::name).toArray(String[]::new);
    }
}
