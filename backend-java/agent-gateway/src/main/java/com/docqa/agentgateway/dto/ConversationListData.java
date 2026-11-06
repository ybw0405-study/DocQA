package com.docqa.agentgateway.dto;

import java.util.List;

public record ConversationListData(
        List<ConversationListItemData> items,
        String nextCursor,
        int limit) {

    public ConversationListData {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
