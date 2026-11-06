package com.docqa.reminder.service;

import com.docqa.reminder.dto.ReminderCreateData;

public record CreateReminderResult(ReminderCreateData data, boolean created) {
}
