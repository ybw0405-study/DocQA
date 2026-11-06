package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.config.AgentProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RateLimiterTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:05Z");

    @Mock
    private StringRedisTemplate redisTemplate;

    private AgentProperties properties;
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.setRateLimitMaxRequests(20);
        rateLimiter = new RateLimiter(
                redisTemplate, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void allowsRequestsAtTheLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(20L);

        assertThatCode(() -> rateLimiter.check(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    void rejectsRequestsAboveTheLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(21L);

        assertThatThrownBy(() -> rateLimiter.check(USER_ID))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).getRetryAfterSeconds())
                        .isGreaterThan(0));
    }

    @Test
    void failsOpenWhenRedisReturnsNoCount() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(null);

        assertThatCode(() -> rateLimiter.check(USER_ID)).doesNotThrowAnyException();
    }
}
