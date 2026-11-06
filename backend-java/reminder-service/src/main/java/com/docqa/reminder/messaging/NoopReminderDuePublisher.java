package com.docqa.reminder.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "docqa.rocketmq.enabled", havingValue = "false")
public class NoopReminderDuePublisher implements ReminderDuePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoopReminderDuePublisher.class);

    @Override
    public void publish(ReminderDueEvent event) {
        LOGGER.warn(
                "RocketMQ is disabled; reminder due event was not published: reminderId={}, occurrenceStartUtc={}",
                event.reminderId(),
                event.occurrenceStartUtc());
    }
}
