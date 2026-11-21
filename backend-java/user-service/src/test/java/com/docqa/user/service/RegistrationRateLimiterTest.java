package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.docqa.user.config.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
class RegistrationRateLimiterTest {

    private static final String GATEWAY_IP = "10.0.0.1";

    @Mock
    private StringRedisTemplate redisTemplate;

    private RegistrationRateLimiter limiter;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setMaxRegistrationsPerWindow(2);
        properties.setRegistrationWindow(Duration.ofSeconds(120));
        limiter = new RegistrationRateLimiter(
                redisTemplate, properties, new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void incrementsAndExpiresAtomicallyInOneLuaScript() {
        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        doReturn(1L).when(redisTemplate).execute(script.capture(), anyList(), anyString());

        assertThat(limiter.check(GATEWAY_IP, "alice").allowed()).isTrue();

        assertThat(script.getValue().getScriptAsString())
                .as("INCR 与 EXPIRE 必须在同一段 Lua 中，避免首次 INCR 后 EXPIRE 失败导致 key 永不过期")
                .contains("INCR")
                .contains("PEXPIRE");
        // 不能再走「INCR 之后再单独 expire」的非原子路径。
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void differentUsernamesDoNotShareOneCollapsedIpQuota() {
        AtomicReference<List<String>> keys = new AtomicReference<>();
        doAnswer(invocation -> {
            keys.set(invocation.getArgument(1));
            return 1L;
        }).when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

        limiter.check(GATEWAY_IP, "alice");
        String aliceKey = keys.get().get(0);
        limiter.check(GATEWAY_IP, "bob");
        String bobKey = keys.get().get(0);

        assertThat(bobKey)
                .as("网关部署下所有 remoteAddr 相同；第二维（用户名指纹）必须让不同用户不共享配额")
                .isNotEqualTo(aliceKey);
        limiter.check(GATEWAY_IP, "alice");
        assertThat(keys.get().get(0))
                .as("同一 (IP, 用户名) 必须落到同一个配额键")
                .isEqualTo(aliceKey);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rejectsOnceTheWindowQuotaIsExhausted() {
        doReturn(3L).when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

        RateLimitDecision decision = limiter.check(GATEWAY_IP, "alice");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isPositive();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void failsOpenWhenRedisIsUnavailable() {
        doThrow(new QueryTimeoutException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

        assertThat(limiter.check(GATEWAY_IP, "alice").allowed()).isTrue();
    }
}
