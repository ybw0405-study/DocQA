package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.ConversationData;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ConversationCreationStore {

    Optional<ConversationCreationRecord> find(UUID userId, String key);

    boolean tryStart(UUID userId, String key, String fingerprint, Instant now);

    void complete(UUID userId, String key, String fingerprint, ConversationData response, Instant now);

    void clear(UUID userId, String key);
}
