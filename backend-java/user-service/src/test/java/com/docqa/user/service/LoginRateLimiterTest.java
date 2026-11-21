package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.docqa.user.config.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class LoginRateLimiterTest {

    private static final String USERNAME = "demo";
    private static final String IP = "127.0.0.1";

    @Mock
    private StringRedisTemplate redisTemplate;

    private MutableClock clock;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AuthProperties properties = new AuthProperties();
        properties.setMaxLoginFailures(5);
        properties.setLoginFailureWindow(Duration.ofSeconds(60));
        limiter = new LoginRateLimiter(redisTemplate, properties, clock);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recordAndCheckUseAtomicLuaAndDualKeys() {
        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        AtomicReference<List<String>> keys = new AtomicReference<>();
        doAnswer(invocation -> {
            keys.set(invocation.getArgument(1));
            return 1L;
        }).when(redisTemplate).execute(script.capture(), anyList(), anyString());

        limiter.recordFailure(USERNAME, IP);

        assertThat(script.getValue().getScriptAsString())
                .as("INCR 与首次 PEXPIRE 必须在同一段 Lua 里，且用户/IP 双键同时计数")
                .contains("INCR")
                .contains("PEXPIRE");
        assertThat(keys.get()).as("用户维度与 IP 维度各一个 key").hasSize(2);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void checkPermitsBelowThresholdAndRejectsAtThreshold() {
        doReturn(4L).when(redisTemplate).execute(any(RedisScript.class), anyList());
        assertThat(limiter.check(USERNAME, IP).allowed()).isTrue();

        doReturn(5L).when(redisTemplate).execute(any(RedisScript.class), anyList());
        RateLimitDecision decision = limiter.check(USERNAME, IP);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isPositive();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dualKeysDoNotShareOneCollapsedGatewayIpQuota() {
        AtomicReference<List<String>> keys = new AtomicReference<>();
        doAnswer(invocation -> {
            keys.set(invocation.getArgument(1));
            return 1L;
        }).when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

        limiter.recordFailure(USERNAME, "10.0.0.1");
        List<String> first = keys.get();
        limiter.recordFailure("other-user", "10.0.0.1");
        List<String> second = keys.get();

        assertThat(second.get(0)).as("用户维度不同 → key 不同").isNotEqualTo(first.get(0));
        assertThat(second.get(1)).as("IP 塌缩到同一网关地址 → IP 维度 key 相同").isEqualTo(first.get(1));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void checkNormalizesUsernameCaseAndWhitespace() {
        AtomicReference<List<String>> keys = new AtomicReference<>();
        doAnswer(invocation -> {
            keys.set(invocation.getArgument(1));
            return 1L;
        }).when(redisTemplate).execute(any(RedisScript.class), anyList());

        limiter.check(" Demo ", IP);
        List<String> normalized = keys.get();
        limiter.check("demo", IP);

        assertThat(keys.get().get(0)).isEqualTo(normalized.get(0));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resetDeletesBothKeys() {
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList());

        limiter.reset(USERNAME, IP);

        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(), anyList());
        assertThat(script.getValue().getScriptAsString()).contains("DEL");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void failsOpenWhenRedisIsUnavailable() {
        doThrow(new QueryTimeoutException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList());

        assertThat(limiter.check(USERNAME, IP).allowed()).isTrue();
        limiter.recordFailure(USERNAME, IP); // 不应抛异常
        limiter.reset(USERNAME, IP);
    }

    @Test
    void rejectsInvalidConfiguration() {
        AuthProperties properties = new AuthProperties();
        properties.setMaxLoginFailures(0);
        properties.setLoginFailureWindow(Duration.ofSeconds(60));

        assertThatThrownBy(() -> new LoginRateLimiter(redisTemplate, properties, clock))
                .isInstanceOf(IllegalStateException.class);
    }
}
