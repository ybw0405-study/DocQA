package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.user.config.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    private static final String USERNAME = "demo";
    private static final String IP = "127.0.0.1";

    private MutableClock clock;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AuthProperties properties = new AuthProperties();
        properties.setMaxLoginFailures(5);
        properties.setLoginFailureWindow(Duration.ofSeconds(60));
        limiter = new LoginRateLimiter(properties, clock);
    }

    @Test
    void allowsFiveFailuresAndBlocksSixthAttempt() {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(limiter.check(USERNAME, IP).allowed()).isTrue();
            limiter.recordFailure(USERNAME, IP);
        }

        RateLimitDecision sixth = limiter.check(USERNAME, IP);

        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void blocksUntilWindowExpires() {
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.recordFailure(USERNAME, IP);
        }

        clock.advance(Duration.ofSeconds(30));
        RateLimitDecision halfway = limiter.check(USERNAME, IP);
        assertThat(halfway.allowed()).isFalse();
        assertThat(halfway.retryAfterSeconds()).isEqualTo(30);

        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.check(USERNAME, IP).allowed()).isTrue();
    }

    @Test
    void successResetsFailures() {
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.recordFailure(USERNAME, IP);
        }
        limiter.reset(USERNAME, IP);

        assertThat(limiter.check(USERNAME, IP).allowed()).isTrue();
    }

    @Test
    void separatesUsernameAndAddress() {
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.recordFailure(USERNAME, IP);
        }

        assertThat(limiter.check(USERNAME, "10.0.0.2").allowed()).isTrue();
        assertThat(limiter.check("other", IP).allowed()).isTrue();
    }

    @Test
    void normalizesUsernameCaseAndWhitespace() {
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.recordFailure(" Demo ", IP);
        }

        assertThat(limiter.check("demo", IP).allowed()).isFalse();
    }

    @Test
    void rejectsInvalidConfiguration() {
        AuthProperties properties = new AuthProperties();
        properties.setMaxLoginFailures(0);
        properties.setLoginFailureWindow(Duration.ofSeconds(60));

        assertThatThrownBy(() -> new LoginRateLimiter(properties, clock))
                .isInstanceOf(IllegalStateException.class);
    }
}
