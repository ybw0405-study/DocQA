package com.docqa.document.messaging;

import java.time.Instant;
import java.util.UUID;

public record VectorChangeRequestedEvent(
        UUID eventId,
        String traceId,
        UUID documentId,
        UUID userId,
        // 删除范围必须绑定租户；没有 tenantId 时 Python 侧拒收，避免跨租户清空向量。
        UUID tenantId,
        String operation,
        String indexVersion,
        Instant occurredAt) {

    public static VectorChangeRequestedEvent deleteDocument(
            String traceId,
            UUID documentId,
            UUID userId,
            UUID tenantId,
            Instant occurredAt) {
        return new VectorChangeRequestedEvent(
                UUID.randomUUID(),
                traceId,
                documentId,
                userId,
                tenantId,
                VectorChangeOperation.DELETE_DOCUMENT.name(),
                null,
                occurredAt);
    }

    public static VectorChangeRequestedEvent deleteVersion(
            String traceId,
            UUID documentId,
            UUID userId,
            UUID tenantId,
            String indexVersion,
            Instant occurredAt) {
        return new VectorChangeRequestedEvent(
                UUID.randomUUID(),
                traceId,
                documentId,
                userId,
                tenantId,
                VectorChangeOperation.DELETE_VERSION.name(),
                indexVersion,
                occurredAt);
    }
}
