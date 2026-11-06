package com.docqa.user.dto;

import com.docqa.user.domain.UserRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * 角色整集替换。空集合不在 Bean Validation 里拦：它不是请求格式错误，
 * 而是「账号必须至少持有 USER」的策略违规，由服务层返回 422。
 */
public record UserRolesUpdateRequest(
        @NotNull @Size(max = 2) Set<UserRole> roles) {
}
