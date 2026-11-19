package com.docqa.document.support;

import java.util.UUID;

/**
 * MinIO 对象键的唯一构造点（§5.2 第 6 条）。
 *
 * <p>新键带租户段 {@code documents/{tenantId}/{userId}/{documentId}/source}，让对象存储按租户
 * 可分片、可证删除时能按租户前缀定位。存量对象键（{@code documents/{userId}/{documentId}/source}）
 * 已逐行落在 document.object_key 里，整条上传/解析/删除链路都从该字段读取而非重新构造，
 * 因此旧对象天然兼容——这与 §3 第 6 条「保留旧路径兼容读取」的口径一致，无需数据迁移。
 */
public final class ObjectKeys {

    private static final String PREFIX = "documents/";

    private ObjectKeys() {
    }

    public static String source(UUID tenantId, UUID userId, UUID documentId) {
        return PREFIX + tenantId + "/" + userId + "/" + documentId + "/source";
    }
}
