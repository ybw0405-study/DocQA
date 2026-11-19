package com.docqa.user.dto;

import java.util.UUID;

/** 供给端点返回值：不泄露 slug 之外的目录信息，租户状态恒为 ACTIVE。 */
public record TenantCreatedData(UUID id, String slug, String status) {
}
