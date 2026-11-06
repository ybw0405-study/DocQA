package com.docqa.reminder.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 单个 {@code VEVENT} 的解析与判定结果，预览与导入返回同构条目。
 *
 * <p>{@code exdates} 用字符串而不是 Instant：ICS 的时刻级与仅日期级两种排除必须原样区分，
 * 仅日期形态（{@code 2026-10-05}）表示「当地日期整天停课」，折算成某个 UTC 时刻就会丢掉这层语义。
 */
public record ScheduleImportItemData(
        String uid,
        String summary,
        int sequence,
        Integer currentSequence,
        String action,
        Instant dtstart,
        String timezone,
        String rrule,
        List<String> exdates,
        Instant nextTriggerAt,
        Instant followingTriggerAt,
        UUID reminderId,
        String description) {

    public ScheduleImportItemData {
        exdates = exdates == null ? List.of() : List.copyOf(exdates);
    }
}
