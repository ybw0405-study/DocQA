package com.docqa.document.messaging;

import java.time.Instant;
import java.util.UUID;

public record ParseRequestedEvent(
        UUID eventId,
        String traceId,
        UUID documentId,
        UUID userId,
        // 004 起数据面读写都强制 tenant_id 谓词，消息必须携带租户信封；
        // 缺失时 Python worker 直接拒收（DOC-TENANT-001），不再静默写空串。
        UUID tenantId,
        String indexVersion,
        String objectKey,
        String fileName,
        String contentType,
        long sizeBytes,
        String md5,
        int retryCount,
        Instant occurredAt) {
}
