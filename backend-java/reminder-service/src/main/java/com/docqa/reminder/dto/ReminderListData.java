package com.docqa.reminder.dto;

import java.util.List;

public record ReminderListData(
        List<ReminderListItemData> items,
        String nextCursor,
        int limit) {

    public ReminderListData {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
