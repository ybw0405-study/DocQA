package com.docqa.reminder.domain;

/**
 * 课表导入的逐事件判定：比较对象是文件里的 {@code SEQUENCE} 与库内同 UID 行的 {@code source_sequence}，
 * 四态互斥且穷尽。{@code UNCHANGED} 与 {@code STALE} 都不是错误，也不产生 SQL 写入。
 */
public enum ScheduleImportAction {
    CREATE,
    UPDATE,
    UNCHANGED,
    STALE
}
