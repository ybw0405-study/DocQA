package com.docqa.document.domain;

import java.util.UUID;

/**
 * 系统级路径（MQ 消费、定时扫描）读到的文档行及其归属租户。
 *
 * <p>这些路径没有 HTTP 租户上下文，不能靠 {@code TenantContext}；租户归属以持久化行为准，
 * 由查询一并返回，避免「先猜租户再查行」的两次查询和跨租户串账。
 */
public record TenantDocument(UUID tenantId, DocumentRow document) {
}
