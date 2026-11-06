package com.docqa.reminder.messaging;

public interface ReminderDuePublisher {

    void publish(ReminderDueEvent event);
}
