package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.ConversationListRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ConversationRepository {

    boolean existsById(UUID tenantId, UUID id);

    boolean existsByIdAndUser(UUID tenantId, UUID id, UUID userId);

    boolean createOwned(UUID tenantId, UUID id, UUID userId, String title, List<UUID> documentScopeIds, Instant now);

    void touch(UUID tenantId, UUID id, Instant now);

    List<ConversationListRow> findUserPage(UUID tenantId, UUID userId, Instant beforeLastMessageAt, UUID beforeId,
            boolean beforeNullGroup, int limit);
}
