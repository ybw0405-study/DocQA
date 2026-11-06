package com.docqa.reminder.service;

import com.docqa.reminder.config.ReminderProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisFiredOccurrenceStore implements FiredOccurrenceStore {

    private static final String CLAIMING = "P";
    private static final String FIRED = "1";

    private final StringRedisTemplate redisTemplate;
    private final ReminderProperties properties;

    public RedisFiredOccurrenceStore(StringRedisTemplate redisTemplate, ReminderProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public Claim claim(UUID reminderId, Instant occurrenceStartUtc) {
        String key = redisKey(reminderId, occurrenceStartUtc);
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                key, CLAIMING, properties.getFiredClaimTtl());
        if (Boolean.TRUE.equals(claimed)) {
            return Claim.CLAIMED;
        }
        String value = redisTemplate.opsForValue().get(key);
        return FIRED.equals(value) ? Claim.FIRED : Claim.IN_PROGRESS;
    }

    @Override
    public void markFired(UUID reminderId, Instant occurrenceStartUtc) {
        redisTemplate.opsForValue().set(
                redisKey(reminderId, occurrenceStartUtc),
                FIRED,
                properties.getFiredTtl());
    }

    @Override
    public void release(UUID reminderId, Instant occurrenceStartUtc) {
        redisTemplate.delete(redisKey(reminderId, occurrenceStartUtc));
    }

    private String redisKey(UUID reminderId, Instant occurrenceStartUtc) {
        return "reminder:fired:" + reminderId + ":"
                + occurrenceStartUtc.truncatedTo(ChronoUnit.SECONDS);
    }
}
