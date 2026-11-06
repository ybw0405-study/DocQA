package com.docqa.reminder.messaging;

/** 载荷缺少必填字段或取值越出枚举域——重投也不会变好，但仍按 RETRY 交给 broker 计入重试与死信。 */
public class ReminderDueRejectedException extends RuntimeException {

    public ReminderDueRejectedException(String message) {
        super(message);
    }
}
