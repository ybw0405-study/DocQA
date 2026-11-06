package com.docqa.reminder.domain;

public enum IdempotencyOperation {
    REMINDER_CREATE,
    /** 与 CREATE 分开命名空间：同一个 key 复用在这两个操作上语义不同。 */
    REMINDER_UPDATE,
    SCHEDULE_IMPORT
}
