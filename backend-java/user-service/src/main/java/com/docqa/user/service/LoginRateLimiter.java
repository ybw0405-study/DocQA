package com.docqa.user.service;

import com.docqa.user.config.AuthProperties;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

    private static final int MAX_ENTRIES = 10_000;

    private final int maxFailures;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, AttemptWindow> attempts = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AttemptWindow> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public LoginRateLimiter(AuthProperties properties, Clock clock) {
        if (properties.getMaxLoginFailures() < 1) {
            throw new IllegalStateException("maxLoginFailures must be positive");
        }
        if (properties.getLoginFailureWindow().isNegative()
                || properties.getLoginFailureWindow().isZero()) {
            throw new IllegalStateException("loginFailureWindow must be positive");
        }
        this.maxFailures = properties.getMaxLoginFailures();
        this.windowMillis = properties.getLoginFailureWindow().toMillis();
        this.clock = clock;
    }

    public synchronized RateLimitDecision check(String username, String remoteAddress) {
        String key = key(username, remoteAddress);
        long now = clock.millis();
        AttemptWindow window = attempts.get(key);
        if (window == null || isExpired(window, now)) {
            return RateLimitDecision.permit();
        }
        if (window.failures < maxFailures) {
            return RateLimitDecision.permit();
        }
        long remainingMillis = window.windowStartMillis + windowMillis - now;
        long retryAfterSeconds = Math.max(1, (remainingMillis + 999) / 1000);
        return RateLimitDecision.reject(retryAfterSeconds);
    }

    public synchronized void recordFailure(String username, String remoteAddress) {
        String key = key(username, remoteAddress);
        long now = clock.millis();
        AttemptWindow window = attempts.get(key);
        if (window == null || isExpired(window, now)) {
            window = new AttemptWindow(now);
            attempts.put(key, window);
        }
        window.failures++;
    }

    public synchronized void reset(String username, String remoteAddress) {
        attempts.remove(key(username, remoteAddress));
    }

    private boolean isExpired(AttemptWindow window, long now) {
        return now - window.windowStartMillis >= windowMillis;
    }

    private String key(String username, String remoteAddress) {
        String normalizedUsername = username == null
                ? ""
                : username.trim().toLowerCase(Locale.ROOT);
        String normalizedAddress = remoteAddress == null ? "" : remoteAddress.trim();
        return normalizedUsername + "|" + normalizedAddress;
    }

    private static final class AttemptWindow {

        private final long windowStartMillis;
        private int failures;

        private AttemptWindow(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
        }
    }
}
