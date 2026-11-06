package com.docqa.reminder.recurrence;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class RruleParser {

    private static final int MAX_LENGTH = 1024;
    private static final int MAX_INTERVAL = 1000;
    private static final int MAX_COUNT = 10_000;
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z]+$");
    private static final Pattern BASIC_UNTIL_PATTERN = Pattern.compile("^\\d{8}T\\d{6}Z$");
    private static final Pattern DAY_PATTERN = Pattern.compile("^(MO|TU|WE|TH|FR|SA|SU)$");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("^-?\\d{1,2}$");
    private static final DateTimeFormatter BASIC_UNTIL_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Set<String> SUPPORTED_KEYS =
            Set.of("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "WKST");
    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "MO", DayOfWeek.MONDAY,
            "TU", DayOfWeek.TUESDAY,
            "WE", DayOfWeek.WEDNESDAY,
            "TH", DayOfWeek.THURSDAY,
            "FR", DayOfWeek.FRIDAY,
            "SA", DayOfWeek.SATURDAY,
            "SU", DayOfWeek.SUNDAY);

    public Rrule parse(String value) {
        Map<String, String> parts = split(value);
        Rrule.Frequency frequency = frequency(require(parts, "FREQ"));
        int interval = interval(parts.get("INTERVAL"));
        Integer count = count(parts.get("COUNT"));
        Instant until = until(parts.get("UNTIL"));
        if (count != null && until != null) {
            throw invalid("rrule", "COUNT and UNTIL must not be used together");
        }
        List<DayOfWeek> byDay = byDay(parts.get("BYDAY"), frequency);
        List<Integer> byMonthDay = byMonthDay(parts.get("BYMONTHDAY"), frequency);
        if (!byDay.isEmpty() && !byMonthDay.isEmpty()) {
            throw invalid("rrule", "BYDAY and BYMONTHDAY must not be combined");
        }
        weekStart(parts.get("WKST"));
        return new Rrule(frequency, interval, count, until, byDay, byMonthDay);
    }

    private Map<String, String> split(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("rrule", "must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw invalid("rrule", "must be at most 1024 characters");
        }
        Map<String, String> parts = new LinkedHashMap<>();
        for (String token : value.split(";", -1)) {
            int separator = token.indexOf('=');
            if (token.isBlank() || separator <= 0 || separator == token.length() - 1) {
                throw invalid("rrule", "each part must use KEY=VALUE syntax");
            }
            String key = token.substring(0, separator);
            String partValue = token.substring(separator + 1);
            if (!KEY_PATTERN.matcher(key).matches()) {
                throw invalid("rrule", "RRULE keys must be uppercase letters");
            }
            if (!SUPPORTED_KEYS.contains(key)) {
                throw invalid("rrule", "unsupported RRULE part: " + key);
            }
            if (parts.putIfAbsent(key, partValue) != null) {
                throw invalid("rrule", "duplicate RRULE part: " + key);
            }
        }
        return parts;
    }

    private Rrule.Frequency frequency(String value) {
        try {
            return Rrule.Frequency.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw invalid(
                    "rrule",
                    "FREQ must be one of DAILY, WEEKLY, MONTHLY or YEARLY; " + value + " is not supported");
        }
    }

    private int interval(String value) {
        if (value == null) {
            return 1;
        }
        int interval = positiveInt(value, "INTERVAL");
        if (interval > MAX_INTERVAL) {
            throw invalid("rrule", "INTERVAL must be at most " + MAX_INTERVAL);
        }
        return interval;
    }

    private Integer count(String value) {
        if (value == null) {
            return null;
        }
        int count = positiveInt(value, "COUNT");
        if (count > MAX_COUNT) {
            throw invalid("rrule", "COUNT must be at most " + MAX_COUNT);
        }
        return count;
    }

    private Instant until(String value) {
        if (value == null) {
            return null;
        }
        try {
            if (BASIC_UNTIL_PATTERN.matcher(value).matches()) {
                return LocalDateTime.parse(value, BASIC_UNTIL_FORMAT)
                        .toInstant(ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.SECONDS);
            }
            return Instant.parse(value).truncatedTo(ChronoUnit.SECONDS);
        } catch (DateTimeParseException ex) {
            throw invalid("rrule", "UNTIL must be a UTC date-time such as 20261231T235959Z");
        }
    }

    private List<DayOfWeek> byDay(String value, Rrule.Frequency frequency) {
        if (value == null) {
            return List.of();
        }
        if (frequency != Rrule.Frequency.WEEKLY && frequency != Rrule.Frequency.MONTHLY) {
            throw invalid("rrule", "BYDAY is only supported with WEEKLY or MONTHLY");
        }
        List<DayOfWeek> days = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            if (!DAY_PATTERN.matcher(token).matches()) {
                throw invalid("rrule", "BYDAY must contain plain weekdays such as MO,WE,FR");
            }
            DayOfWeek day = DAYS.get(token);
            if (days.contains(day)) {
                throw invalid("rrule", "BYDAY must not contain duplicates");
            }
            days.add(day);
        }
        days.sort(DayOfWeek::compareTo);
        return List.copyOf(days);
    }

    private List<Integer> byMonthDay(String value, Rrule.Frequency frequency) {
        if (value == null) {
            return List.of();
        }
        if (frequency != Rrule.Frequency.MONTHLY && frequency != Rrule.Frequency.YEARLY) {
            throw invalid("rrule", "BYMONTHDAY is only supported with MONTHLY or YEARLY");
        }
        List<Integer> days = new ArrayList<>();
        for (String token : value.split(",", -1)) {
            if (!MONTH_DAY_PATTERN.matcher(token).matches()) {
                throw invalid("rrule", "BYMONTHDAY must contain numbers between -31 and 31");
            }
            int day = Integer.parseInt(token);
            if (day == 0 || day < -31 || day > 31) {
                throw invalid("rrule", "BYMONTHDAY must contain numbers between -31 and 31, excluding 0");
            }
            if (days.contains(day)) {
                throw invalid("rrule", "BYMONTHDAY must not contain duplicates");
            }
            days.add(day);
        }
        days.sort(Integer::compareTo);
        return List.copyOf(days);
    }

    private void weekStart(String value) {
        if (value != null && !"MO".equals(value)) {
            throw invalid("rrule", "only WKST=MO is supported");
        }
    }

    private int positiveInt(String value, String key) {
        try {
            int number = Integer.parseInt(value);
            if (number < 1) {
                throw invalid("rrule", key + " must be greater than 0");
            }
            return number;
        } catch (NumberFormatException ex) {
            throw invalid("rrule", key + " must be a positive integer");
        }
    }

    private String require(Map<String, String> parts, String key) {
        String value = parts.get(key);
        if (value == null) {
            throw invalid("rrule", "FREQ is required");
        }
        return value;
    }

    private ReminderRuleException invalid(String field, String message) {
        return new ReminderRuleException(field, message);
    }
}
