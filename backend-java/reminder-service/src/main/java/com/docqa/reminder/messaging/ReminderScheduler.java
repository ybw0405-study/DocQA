package com.docqa.reminder.messaging;

import com.docqa.reminder.service.ReminderDueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReminderScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderDueService reminderDueService;

    public ReminderScheduler(ReminderDueService reminderDueService) {
        this.reminderDueService = reminderDueService;
    }

    @Scheduled(
            fixedDelayString = "${docqa.reminder.scan-interval-ms:5000}",
            initialDelayString = "${docqa.reminder.scan-initial-delay-ms:5000}")
    public void scanDueReminders() {
        try {
            reminderDueService.dispatchDue();
        } catch (RuntimeException ex) {
            LOGGER.error("Reminder scan aborted", ex);
        }
    }
}
