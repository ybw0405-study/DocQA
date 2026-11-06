package com.docqa.agentgateway.service;

import java.util.UUID;

public interface ConversationLock {

    boolean tryLock(UUID conversationId, String owner);

    void unlock(UUID conversationId, String owner);
}
