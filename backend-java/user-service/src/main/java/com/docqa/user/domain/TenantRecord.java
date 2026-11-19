package com.docqa.user.domain;

import java.util.UUID;

/**
 * 租户控制面记录。
 *
 * <p>{@code tenant} 表本身没有 tenant_id 列——它是「租户清单」，不是某个租户内的业务数据。
 * 因此按 slug 查这张表属于解析租户身份，而不是在租户内做业务查询。
 */
public record TenantRecord(UUID id, String slug, String status) {
}
