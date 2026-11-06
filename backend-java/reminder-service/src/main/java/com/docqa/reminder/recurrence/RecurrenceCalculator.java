package com.docqa.reminder.recurrence;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

public final class RecurrenceCalculator {

    private static final int MAX_ITERATIONS = 50_000;
    private static final int MAX_YEAR = 9999;

    public Optional<Instant> nextAtOrAfter(
            Rrule rule,
            Instant dtstart,
            ZoneId zone,
            Set<Instant> exdates,
            Instant threshold) {
        return nextAtOrAfter(rule, dtstart, zone, exdates, Set.of(), threshold);
    }

    public Optional<Instant> nextAtOrAfter(
            Rrule rule,
            Instant dtstart,
            ZoneId zone,
            Set<Instant> exdates,
            Set<LocalDate> exdateDates,
            Instant threshold) {
        Instant seed = dtstart.truncatedTo(ChronoUnit.SECONDS);
        Instant lowerBound = threshold.truncatedTo(ChronoUnit.SECONDS);
        ZonedDateTime localSeed = seed.atZone(zone);
        LocalTime time = localSeed.toLocalTime();
        int occurrences = 0;

        for (int step = 0; step < MAX_ITERATIONS; step++) {
            if (rule.frequency() == Rrule.Frequency.YEARLY
                    && (long) localSeed.getYear() + (long) step * rule.interval() > MAX_YEAR) {
                return Optional.empty();
            }
            for (Instant candidate : candidates(rule, localSeed, time, zone, step)) {
                if (candidate.isBefore(seed)) {
                    continue;
                }
                occurrences++;
                if (rule.count() != null && occurrences > rule.count()) {
                    return Optional.empty();
                }
                if (rule.until() != null && candidate.isAfter(rule.until())) {
                    return Optional.empty();
                }
                if (isExcluded(candidate, zone, exdates, exdateDates)) {
                    continue;
                }
                if (!candidate.isBefore(lowerBound)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Instant> nextAfter(
            Rrule rule,
            Instant dtstart,
            ZoneId zone,
            Set<Instant> exdates,
            Instant exclusive) {
        return nextAfter(rule, dtstart, zone, exdates, Set.of(), exclusive);
    }

    public Optional<Instant> nextAfter(
            Rrule rule,
            Instant dtstart,
            ZoneId zone,
            Set<Instant> exdates,
            Set<LocalDate> exdateDates,
            Instant exclusive) {
        return nextAtOrAfter(rule, dtstart, zone, exdates, exdateDates, exclusive.plusSeconds(1));
    }

    /**
     * 两种排除的判等粒度不同：时刻型按精确 Instant，日期型按 zone 下的当地日期。
     * 被排除的 occurrence 仍然消耗 COUNT，与时刻型排除保持同一口径。
     */
    private static boolean isExcluded(
            Instant candidate,
            ZoneId zone,
            Set<Instant> exdates,
            Set<LocalDate> exdateDates) {
        if (exdates.contains(candidate)) {
            return true;
        }
        return !exdateDates.isEmpty()
                && exdateDates.contains(candidate.atZone(zone).toLocalDate());
    }

    private List<Instant> candidates(
            Rrule rule,
            ZonedDateTime localSeed,
            LocalTime time,
            ZoneId zone,
            int step) {
        return switch (rule.frequency()) {
            case DAILY -> List.of(instant(localSeed.toLocalDate().plusDays((long) step * rule.interval()), time, zone));
            case WEEKLY -> weekly(rule, localSeed, time, zone, step);
            case MONTHLY -> monthly(rule, localSeed, time, zone, step);
            case YEARLY -> yearly(rule, localSeed, time, zone, step);
        };
    }

    private List<Instant> weekly(
            Rrule rule,
            ZonedDateTime localSeed,
            LocalTime time,
            ZoneId zone,
            int step) {
        LocalDate weekStart = localSeed.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .plusWeeks((long) step * rule.interval());
        List<DayOfWeek> days = rule.byDay().isEmpty()
                ? List.of(localSeed.getDayOfWeek())
                : rule.byDay();
        return days.stream()
                .map(day -> instant(weekStart.plusDays((long) day.getValue() - 1), time, zone))
                .toList();
    }

    private List<Instant> monthly(
            Rrule rule,
            ZonedDateTime localSeed,
            LocalTime time,
            ZoneId zone,
            int step) {
        LocalDate monthStart = localSeed.toLocalDate()
                .withDayOfMonth(1)
                .plusMonths((long) step * rule.interval());
        int length = monthStart.lengthOfMonth();
        List<Integer> days;
        if (!rule.byMonthDay().isEmpty()) {
            days = rule.byMonthDay().stream()
                    .map(day -> day > 0 ? day : length + 1 + day)
                    .filter(day -> day >= 1 && day <= length)
                    .distinct()
                    .sorted()
                    .toList();
        } else if (!rule.byDay().isEmpty()) {
            days = IntStream.rangeClosed(1, length)
                    .filter(day -> rule.byDay().contains(monthStart.withDayOfMonth(day).getDayOfWeek()))
                    .boxed()
                    .toList();
        } else {
            int day = localSeed.getDayOfMonth();
            days = day <= length ? List.of(day) : List.of();
        }
        return days.stream()
                .map(day -> instant(monthStart.withDayOfMonth(day), time, zone))
                .toList();
    }

    private List<Instant> yearly(
            Rrule rule,
            ZonedDateTime localSeed,
            LocalTime time,
            ZoneId zone,
            int step) {
        int year = localSeed.getYear() + step * rule.interval();
        LocalDate monthStart = LocalDate.of(year, localSeed.getMonth(), 1);
        int length = monthStart.lengthOfMonth();
        List<Integer> days;
        if (!rule.byMonthDay().isEmpty()) {
            days = rule.byMonthDay().stream()
                    .map(day -> day > 0 ? day : length + 1 + day)
                    .filter(day -> day >= 1 && day <= length)
                    .distinct()
                    .sorted()
                    .toList();
        } else {
            int day = localSeed.getDayOfMonth();
            days = day <= length ? List.of(day) : List.of();
        }
        return days.stream()
                .map(day -> instant(monthStart.withDayOfMonth(day), time, zone))
                .toList();
    }

    private Instant instant(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone).toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}
