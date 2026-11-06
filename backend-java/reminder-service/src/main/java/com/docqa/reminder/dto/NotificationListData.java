package com.docqa.reminder.dto;

import java.util.List;

public record NotificationListData(
        List<NotificationData> items,
        String nextCursor,
        int limit) {

    public NotificationListData {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
