package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisTokenRevocationStoreTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String JTI = "11111111-1111-4111-8111-111111111111";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisTokenRevocationStore store;

    @BeforeEach
    void setUp() {
        store = new RedisTokenRevocationStore(redisTemplate);
    }

    @Test
    void detectsRevokedTokenId() {
        when(redisTemplate.hasKey("auth:revoked:jti:" + JTI)).thenReturn(true);

        assertThat(store.isRevoked(JTI, USER_ID, Instant.parse("2026-01-01T00:00:00Z"))).isTrue();
    }

    @Test
    void detectsTokenIssuedBeforeUserWatermark() {
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        when(redisTemplate.hasKey("auth:revoked:jti:" + JTI)).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:revoked:user:" + USER_ID)).thenReturn("1767225600001");

        assertThat(store.isRevoked(JTI, USER_ID, issuedAt)).isTrue();
    }

    @Test
    void acceptsTokenIssuedExactlyAtWatermark() {
        Instant issuedAt = Instant.ofEpochMilli(1767225600000L);
        when(redisTemplate.hasKey("auth:revoked:jti:" + JTI)).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:revoked:user:" + USER_ID)).thenReturn("1767225600000");

        assertThat(store.isRevoked(JTI, USER_ID, issuedAt)).isFalse();
    }

    @Test
    void acceptsTokenWhenNoWatermarkExists() {
        when(redisTemplate.hasKey("auth:revoked:jti:" + JTI)).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:revoked:user:" + USER_ID)).thenReturn(null);

        assertThat(store.isRevoked(JTI, USER_ID, Instant.parse("2026-01-01T00:00:00Z"))).isFalse();
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        when(redisTemplate.hasKey("auth:revoked:jti:" + JTI))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(store.isRevoked(JTI, USER_ID, Instant.parse("2026-01-01T00:00:00Z"))).isFalse();
    }

    @Test
    void failsOpenWhenWatermarkIsNotNumeric() {
        when(redisTemplate.hasKey("auth:revoked:jti:" + JTI)).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:revoked:user:" + USER_ID)).thenReturn("not-a-millis");

        assertThat(store.isRevoked(JTI, USER_ID, Instant.parse("2026-01-01T00:00:00Z"))).isFalse();
    }
}
