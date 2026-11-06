package com.docqa.reminder.recurrence;

public class ReminderRuleException extends RuntimeException {

    private final String field;

    public ReminderRuleException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
