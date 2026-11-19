package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.MessageHistoryRow;
import com.docqa.agentgateway.dto.StoredMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MessageRepository {

    void insert(
            UUID tenantId,
            UUID id,
            UUID conversationId,
            String role,
            String content,
            int tokenCount,
            String clientRequestId,
            String traceId,
            String metadataJson,
            Instant createdAt);

    List<StoredMessage> findRecent(UUID tenantId, UUID conversationId, int limit);

    List<MessageHistoryRow> findPage(UUID tenantId, UUID conversationId, Instant beforeCreatedAt, UUID beforeId,
            int limit);
}
