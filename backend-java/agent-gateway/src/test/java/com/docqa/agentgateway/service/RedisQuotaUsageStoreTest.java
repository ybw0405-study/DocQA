package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RedisQuotaUsageStoreTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    private static final String KEY = "agent:quota:usage:" + USER_ID + ":2026-11-03";

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisQuotaUsageStore store;

    @BeforeEach
    void setUp() {
        store = new RedisQuotaUsageStore(redisTemplate);
    }

    @Test
    void reservesWithTheUtcDayKeyAndTheConfiguredLimits() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        assertThat(store.tryConsume(USER_ID, DAY, 30L, 60_000L)).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of(KEY)), eq("30"), eq("60000"), eq("172800"));
    }

    @Test
    void treatsAbsentLimitsAsUnlimitedSentinels() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        assertThat(store.tryConsume(USER_ID, DAY, null, null)).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of(KEY)), eq("-1"), eq("-1"), eq("172800"));
    }

    @Test
    void reportsRefusalWhenTheScriptRejects() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        assertThat(store.tryConsume(USER_ID, DAY, 30L, null)).isFalse();
    }

    @Test
    void failsClosedWhenTheScriptReturnsNothing() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        assertThatThrownBy(() -> store.tryConsume(USER_ID, DAY, 30L, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addsTokensWithTheSameDayKeyAndTtl() {
        store.addTokens(USER_ID, DAY, 120L);

        verify(redisTemplate).execute(
                any(RedisScript.class), eq(List.of(KEY)), eq("120"), eq("172800"));
    }

    @Test
    void propagatesRedisFailures() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> store.tryConsume(USER_ID, DAY, 30L, null))
                .isInstanceOf(RedisConnectionFailureException.class);
    }
}
