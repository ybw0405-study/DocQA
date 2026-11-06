package com.docqa.agentgateway.dto;

import java.util.List;
import java.util.UUID;

public record MessageHistoryData(
        UUID conversationId,
        List<MessageHistoryItemData> items,
        String nextCursor,
        int limit) {

    public MessageHistoryData {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
