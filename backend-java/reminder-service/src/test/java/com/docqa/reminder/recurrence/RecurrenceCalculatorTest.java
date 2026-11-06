package com.docqa.reminder.recurrence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecurrenceCalculatorTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final RruleParser parser = new RruleParser();
    private final RecurrenceCalculator calculator = new RecurrenceCalculator();

    @Test
    void nextAtOrAfterIsInclusive() {
        Rrule rule = parser.parse("FREQ=DAILY");
        Instant start = instant("2026-01-01T09:00:00Z");

        assertThat(calculator.nextAtOrAfter(rule, start, UTC, Set.of(), start)).contains(start);
    }

    @Test
    void nextAfterSkipsExdates() {
        Rrule rule = parser.parse("FREQ=DAILY");
        Instant start = instant("2026-01-01T09:00:00Z");
        Set<Instant> exdates = Set.of(instant("2026-01-02T09:00:00Z"));

        assertThat(next(rule, start, UTC, exdates, start))
                .contains(instant("2026-01-03T09:00:00Z"));
    }

    @Test
    void weeklyByDayReturnsAllConfiguredWeekdays() {
        Rrule rule = parser.parse("FREQ=WEEKLY;BYDAY=MO,WE");
        Instant start = instant("2026-01-05T09:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), start))
                .contains(instant("2026-01-07T09:00:00Z"));
    }

    @Test
    void weeklyIntervalSkipsWeeks() {
        Rrule rule = parser.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO");
        Instant start = instant("2026-01-05T09:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), start))
                .contains(instant("2026-01-19T09:00:00Z"));
    }

    @Test
    void weeklyUntilEndsRecurrence() {
        Rrule rule = parser.parse("FREQ=WEEKLY;BYDAY=MO;UNTIL=20260112T000000Z");
        Instant start = instant("2026-01-05T09:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), start)).isEmpty();
    }

    @Test
    void countLimitsOccurrences() {
        Rrule rule = parser.parse("FREQ=DAILY;COUNT=3");
        Instant start = instant("2026-01-01T09:00:00Z");
        Instant second = instant("2026-01-02T09:00:00Z");
        Instant third = instant("2026-01-03T09:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), start)).contains(second);
        assertThat(next(rule, start, UTC, Set.of(), second)).contains(third);
        assertThat(next(rule, start, UTC, Set.of(), third)).isEmpty();
    }

    @Test
    void countIncludesExcludedCandidates() {
        Rrule rule = parser.parse("FREQ=DAILY;COUNT=2");
        Instant start = instant("2026-01-01T09:00:00Z");
        Instant second = instant("2026-01-02T09:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(start), start.minusSeconds(1))).contains(second);
        assertThat(next(rule, start, UTC, Set.of(start), second)).isEmpty();
    }

    @Test
    void untilIsInclusive() {
        Rrule rule = parser.parse("FREQ=DAILY;UNTIL=20260103T090000Z");
        Instant start = instant("2026-01-01T09:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), instant("2026-01-02T09:00:00Z")))
                .contains(instant("2026-01-03T09:00:00Z"));
    }

    @Test
    void monthlyByMonthDaySkipsShortMonths() {
        Rrule rule = parser.parse("FREQ=MONTHLY;BYMONTHDAY=31");
        Instant start = instant("2026-01-31T08:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), start))
                .contains(instant("2026-03-31T08:00:00Z"));
    }

    @Test
    void monthlySeedDaySkipsShortMonths() {
        Rrule rule = parser.parse("FREQ=MONTHLY");
        Instant start = instant("2026-01-31T08:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), start))
                .contains(instant("2026-03-31T08:00:00Z"));
    }

    @Test
    void monthlyNegativeMonthDayUsesMonthLength() {
        Rrule rule = parser.parse("FREQ=MONTHLY;BYMONTHDAY=-1");
        Instant start = instant("2026-01-31T08:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), start))
                .contains(instant("2026-02-28T08:00:00Z"));
    }

    @Test
    void monthlyByDayReturnsEveryMatchingWeekday() {
        Rrule rule = parser.parse("FREQ=MONTHLY;BYDAY=MO");
        Instant start = instant("2026-01-05T09:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), start))
                .contains(instant("2026-01-12T09:00:00Z"));
    }

    @Test
    void weeklyByDayKeepsLocalTimeAcrossDaylightSaving() {
        Rrule rule = parser.parse("FREQ=WEEKLY;BYDAY=SU");
        Instant start = instant("2026-03-01T14:00:00Z");

        assertThat(next(rule, start, NEW_YORK, Set.of(), start))
                .contains(instant("2026-03-08T13:00:00Z"));
    }

    @Test
    void yearlyLeapDaySkipsNonLeapYears() {
        Rrule rule = parser.parse("FREQ=YEARLY");
        Instant start = instant("2024-02-29T00:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), start))
                .contains(instant("2028-02-29T00:00:00Z"));
    }

    @Test
    void yearlyByMonthDayCanUseLastDayOfMonth() {
        Rrule rule = parser.parse("FREQ=YEARLY;BYMONTHDAY=-1");
        Instant start = instant("2026-01-31T00:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), start))
                .contains(instant("2027-01-31T00:00:00Z"));
    }

    @Test
    void returnsEmptyWhenThresholdIsBeyondUntil() {
        Rrule rule = parser.parse("FREQ=DAILY;UNTIL=20260110T090000Z");
        Instant start = instant("2026-01-01T09:00:00Z");

        assertThat(next(rule, start, UTC, Set.of(), instant("2026-02-01T00:00:00Z"))).isEmpty();
    }

    private Optional<Instant> next(
            Rrule rule,
            Instant start,
            ZoneId zone,
            Set<Instant> exdates,
            Instant exclusive) {
        return calculator.nextAfter(rule, start, zone, exdates, exclusive);
    }

    private static Instant instant(String value) {
        return Instant.parse(value);
    }
}
