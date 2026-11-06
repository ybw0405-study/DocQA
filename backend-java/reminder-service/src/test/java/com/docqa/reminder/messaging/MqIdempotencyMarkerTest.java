package com.docqa.reminder.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.reminder.config.ReminderProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 这层只是**加速**：Redis 读写失败一律降级成「未处理」，让请求继续走到唯一键。
 * 把它写成幂等保证会让 Redis 停机变成通知丢失，因此本测试钉的就是这两处不抛。
 */
class MqIdempotencyMarkerTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ReminderProperties properties = new ReminderProperties();
    private final MqIdempotencyMarker marker = new MqIdempotencyMarker(redisTemplate, properties);

    @Test
    void theKeyNamespaceCombinesTopicAndTransportId() {
        when(redisTemplate.hasKey("mq:idem:reminder-due:MSG-1")).thenReturn(true);

        assertThat(marker.alreadyProcessed("reminder-due", "MSG-1")).isTrue();
        verify(redisTemplate).hasKey("mq:idem:reminder-due:MSG-1");
    }

    @Test
    void anAbsentKeyIsReadAsUntreated() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        assertThat(marker.alreadyProcessed("reminder-due", "MSG-2")).isFalse();
    }

    @Test
    void aRedisReadFailureDegradesToUntreatedRatherThanSkippingTheRow() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThat(marker.alreadyProcessed("reminder-due", "MSG-3")).isFalse();
    }

    @Test
    void aRedisWriteFailureNeverUndoesACommittedRow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new IllegalStateException("redis down"))
                .when(valueOperations).set(any(), any(), any(Duration.class));

        assertThatCode(() -> marker.markProcessed("reminder-due", "MSG-4")).doesNotThrowAnyException();
    }

    @Test
    void theMarkerCarriesTheConfiguredTimeToLive() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        marker.markProcessed("reminder-due", "MSG-5");

        verify(valueOperations).set("mq:idem:reminder-due:MSG-5", "1", Duration.ofDays(7));
    }
}
