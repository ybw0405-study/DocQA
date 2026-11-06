package com.docqa.reminder.schedule;

import com.docqa.reminder.recurrence.Rrule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个 {@code VEVENT} 的解析结果，与 {@code reminder} 的一行同构（禁止按 occurrence 拆行）。
 *
 * <p>{@code exdates} 与 {@code exdateDates} 分列，因为两者判重粒度不同：时刻级是精确 Instant 相等，
 * 仅日期级按 {@code timezone} 下的当地日期排除整天。响应里的 {@code exdates} 数组由
 * {@link #exdateValues()} 生成，两型原样区分。
 */
public record IcsEvent(
        String uid,
        String summary,
        String description,
        int sequence,
        String rrule,
        Rrule rule,
        Instant dtstart,
        String timezone,
        List<Instant> exdates,
        List<LocalDate> exdateDates) {

    public IcsEvent {
        exdates = exdates == null ? List.of() : List.copyOf(exdates);
        exdateDates = exdateDates == null ? List.of() : List.copyOf(exdateDates);
    }

    /** 契约里 {@code ScheduleImportItemData.exdates} 的展示口径：时刻级在前、仅日期在后，各自升序。 */
    public List<String> exdateValues() {
        List<String> values = new ArrayList<>(exdates.size() + exdateDates.size());
        exdates.stream().map(Instant::toString).sorted().forEach(values::add);
        exdateDates.stream().map(LocalDate::toString).sorted().forEach(values::add);
        return List.copyOf(values);
    }
}
