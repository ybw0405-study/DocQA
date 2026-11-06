package com.docqa.reminder.recurrence;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;

public record Rrule(
        Frequency frequency,
        int interval,
        Integer count,
        Instant until,
        List<DayOfWeek> byDay,
        List<Integer> byMonthDay) {

    public enum Frequency {
        DAILY,
        WEEKLY,
        MONTHLY,
        YEARLY
    }

    public Rrule {
        byDay = byDay == null ? List.of() : List.copyOf(byDay);
        byMonthDay = byMonthDay == null ? List.of() : List.copyOf(byMonthDay);
    }

    public static Rrule dailyOnce() {
        return new Rrule(Frequency.DAILY, 1, 1, null, List.of(), List.of());
    }
}
