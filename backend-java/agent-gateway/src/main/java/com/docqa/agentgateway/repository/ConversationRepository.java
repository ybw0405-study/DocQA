package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.dto.ConversationListRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ConversationRepository {

    boolean existsById(UUID id);

    boolean existsByIdAndUser(UUID id, UUID userId);

    boolean createOwned(UUID id, UUID userId, String title, List<UUID> documentScopeIds, Instant now);

    void touch(UUID id, Instant now);

    List<ConversationListRow> findUserPage(UUID userId, Instant beforeLastMessageAt, UUID beforeId,
            boolean beforeNullGroup, int limit);
}
