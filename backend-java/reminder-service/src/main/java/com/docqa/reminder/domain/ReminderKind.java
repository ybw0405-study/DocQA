package com.docqa.reminder.domain;

public enum ReminderKind {
    DEADLINE,
    CUSTOM,
    /** 只出现在读侧：`ReminderCreateRequest` 的 `@JsonSubTypes` 未登记本值，提交它直接 400。 */
    COURSE;

    public static ReminderKind fromSourceType(String sourceType) {
        if ("DEADLINE".equals(sourceType)) {
            return DEADLINE;
        }
        if ("COURSE".equals(sourceType)) {
            return COURSE;
        }
        return CUSTOM;
    }
}
