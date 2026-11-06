package com.docqa.reminder.dto;

import java.util.List;

/**
 * 预览结果：逐事件判定与四类计数，不含任何写入副作用。
 * 预览不是承诺，最终以导入响应的 {@code items} 与计数为准。
 */
public record SchedulePreviewData(
        int itemCount,
        int createCount,
        int updateCount,
        int unchangedCount,
        int staleCount,
        String fallbackTimezone,
        int leadMinutes,
        List<ScheduleImportItemData> items) {

    public SchedulePreviewData {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
