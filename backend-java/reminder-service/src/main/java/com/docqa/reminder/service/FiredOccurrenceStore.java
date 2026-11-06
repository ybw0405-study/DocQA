package com.docqa.reminder.service;

import java.time.Instant;
import java.util.UUID;

public interface FiredOccurrenceStore {

    enum Claim {
        CLAIMED,
        IN_PROGRESS,
        FIRED
    }

    Claim claim(UUID reminderId, Instant occurrenceStartUtc);

    void markFired(UUID reminderId, Instant occurrenceStartUtc);

    void release(UUID reminderId, Instant occurrenceStartUtc);
}
