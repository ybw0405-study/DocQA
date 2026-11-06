package com.docqa.document.dto;

import java.util.List;

public record DocumentListData(
        List<DocumentListItemData> items,
        String nextCursor,
        int limit) {

    public DocumentListData {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
