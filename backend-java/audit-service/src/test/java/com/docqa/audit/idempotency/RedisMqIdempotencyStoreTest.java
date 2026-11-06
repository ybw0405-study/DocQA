package com.docqa.audit.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.audit.config.AuditProperties;
import java.time.Duration;
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
class RedisMqIdempotencyStoreTest {

    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-a1b2-7c33-9d01-1234567890ab");
    private static final String KEY = RedisMqIdempotencyStore.KEY_PREFIX + EVENT_ID;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final AuditProperties properties = new AuditProperties();
    private RedisMqIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new RedisMqIdempotencyStore(redisTemplate, properties);
    }

    @Test
    void reportsProcessedWhenTheKeyExists() {
        when(redisTemplate.hasKey(KEY)).thenReturn(true);

        assertThat(store.isProcessed(EVENT_ID)).isTrue();
    }

    @Test
    void storesTheMarkerWithTheConfiguredTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        store.markProcessed(EVENT_ID);

        verify(valueOperations).set(KEY, "1", Duration.ofDays(7));
    }

    @Test
    void failsOpenWhenRedisCannotBeRead() {
        when(redisTemplate.hasKey(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(store.isProcessed(EVENT_ID)).isFalse();
    }

    @Test
    void doesNotPropagateMarkerWriteFailures() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(KEY, "1", Duration.ofDays(7));

        assertThatCode(() -> store.markProcessed(EVENT_ID)).doesNotThrowAnyException();
    }
}
