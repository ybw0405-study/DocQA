package com.docqa.reminder.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RruleParserTest {

    private final RruleParser parser = new RruleParser();

    @Test
    void parsesDailyInterval() {
        Rrule rule = parser.parse("FREQ=DAILY;INTERVAL=2");

        assertThat(rule.frequency()).isEqualTo(Rrule.Frequency.DAILY);
        assertThat(rule.interval()).isEqualTo(2);
        assertThat(rule.count()).isNull();
        assertThat(rule.until()).isNull();
        assertThat(rule.byDay()).isEmpty();
        assertThat(rule.byMonthDay()).isEmpty();
    }

    @Test
    void parsesWeeklyDaysSortedByWeekday() {
        Rrule rule = parser.parse("FREQ=WEEKLY;BYDAY=FR,MO;WKST=MO");

        assertThat(rule.byDay()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    }

    @Test
    void parsesBasicUntilAsUtcInstant() {
        Rrule rule = parser.parse("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T235959Z");

        assertThat(rule.until()).isEqualTo(Instant.parse("2026-12-31T23:59:59Z"));
    }

    @Test
    void parsesExtendedUntil() {
        Rrule rule = parser.parse("FREQ=DAILY;UNTIL=2026-12-31T23:59:59Z");

        assertThat(rule.until()).isEqualTo(Instant.parse("2026-12-31T23:59:59Z"));
    }

    @Test
    void parsesMonthlyNegativeMonthDay() {
        Rrule rule = parser.parse("FREQ=MONTHLY;BYMONTHDAY=-1,15");

        assertThat(rule.byMonthDay()).containsExactly(-1, 15);
    }

    @Test
    void parsesYearlyMonthDay() {
        Rrule rule = parser.parse("FREQ=YEARLY;BYMONTHDAY=31");

        assertThat(rule.frequency()).isEqualTo(Rrule.Frequency.YEARLY);
        assertThat(rule.byMonthDay()).containsExactly(31);
    }

    @Test
    void rejectsMissingFrequency() {
        assertInvalid("INTERVAL=2");
    }

    @Test
    void rejectsUnsupportedFrequencies() {
        assertInvalid("FREQ=SECONDLY");
        assertInvalid("FREQ=MINUTELY");
        assertInvalid("FREQ=HOURLY");
    }

    @Test
    void rejectsLowercaseFrequency() {
        assertInvalid("FREQ=daily");
        assertInvalid("freq=DAILY");
    }

    @Test
    void rejectsInvalidInterval() {
        assertInvalid("FREQ=DAILY;INTERVAL=0");
        assertInvalid("FREQ=DAILY;INTERVAL=-1");
        assertInvalid("FREQ=DAILY;INTERVAL=1001");
        assertInvalid("FREQ=DAILY;INTERVAL=two");
    }

    @Test
    void rejectsInvalidCount() {
        assertInvalid("FREQ=DAILY;COUNT=0");
        assertInvalid("FREQ=DAILY;COUNT=10001");
        assertInvalid("FREQ=DAILY;COUNT=1.5");
    }

    @Test
    void rejectsCountTogetherWithUntil() {
        assertInvalid("FREQ=DAILY;COUNT=2;UNTIL=20261231T235959Z");
    }

    @Test
    void rejectsMalformedUntil() {
        assertInvalid("FREQ=DAILY;UNTIL=2026-12-31");
        assertInvalid("FREQ=DAILY;UNTIL=20261331T235959Z");
    }

    @Test
    void rejectsByDayForFrequenciesWithoutWeekdaySemantics() {
        assertInvalid("FREQ=DAILY;BYDAY=MO");
        assertInvalid("FREQ=YEARLY;BYDAY=MO");
    }

    @Test
    void rejectsOrdinalByDay() {
        assertInvalid("FREQ=MONTHLY;BYDAY=1MO");
        assertInvalid("FREQ=WEEKLY;BYDAY=-1FR");
    }

    @Test
    void rejectsDuplicateByDay() {
        assertInvalid("FREQ=WEEKLY;BYDAY=MO,MO");
    }

    @Test
    void rejectsByMonthDayForDailyOrWeekly() {
        assertInvalid("FREQ=DAILY;BYMONTHDAY=1");
        assertInvalid("FREQ=WEEKLY;BYMONTHDAY=1");
    }

    @Test
    void rejectsInvalidByMonthDay() {
        assertInvalid("FREQ=MONTHLY;BYMONTHDAY=0");
        assertInvalid("FREQ=MONTHLY;BYMONTHDAY=32");
        assertInvalid("FREQ=MONTHLY;BYMONTHDAY=-32");
        assertInvalid("FREQ=MONTHLY;BYMONTHDAY=MO");
    }

    @Test
    void rejectsByDayCombinedWithByMonthDay() {
        assertInvalid("FREQ=MONTHLY;BYDAY=MO;BYMONTHDAY=1");
    }

    @Test
    void rejectsWeekStartOtherThanMonday() {
        assertInvalid("FREQ=WEEKLY;BYDAY=MO;WKST=SU");
    }

    @Test
    void rejectsUnsupportedParts() {
        assertInvalid("FREQ=DAILY;BYSETPOS=1");
        assertInvalid("FREQ=DAILY;BYMONTH=1");
    }

    @Test
    void rejectsMalformedSyntax() {
        assertInvalid("");
        assertInvalid(" ");
        assertInvalid("FREQ=DAILY;");
        assertInvalid("FREQ=DAILY;INTERVAL=");
        assertInvalid("=DAILY");
        assertInvalid("FREQ=DAILY;COUNT=1;COUNT=2");
    }

    @Test
    void rejectsOverlongValue() {
        assertInvalid("FREQ=DAILY;UNKNOWN=" + "a".repeat(1100));
    }

    @Test
    void acceptsExplicitWeekStartMonday() {
        assertThat(parser.parse("FREQ=WEEKLY;BYDAY=MO;WKST=MO").frequency())
                .isEqualTo(Rrule.Frequency.WEEKLY);
    }

    @Test
    void acceptedRuleHasImmutableLists() {
        Rrule rule = parser.parse("FREQ=WEEKLY;BYDAY=MO,WE");

        assertThat(rule.byDay()).isUnmodifiable();
        assertThat(rule.byMonthDay()).isUnmodifiable();
        assertThat(rule.byDay()).isEqualTo(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY));
    }

    private void assertInvalid(String value) {
        assertThatThrownBy(() -> parser.parse(value))
                .isInstanceOf(ReminderRuleException.class)
                .satisfies(ex -> assertThat(((ReminderRuleException) ex).getField()).isEqualTo("rrule"));
    }
}
