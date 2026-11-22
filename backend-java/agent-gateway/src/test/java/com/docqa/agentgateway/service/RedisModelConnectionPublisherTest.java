package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.config.ModelConnectionProperties;
import com.docqa.agentgateway.repository.ModelConnectionRow;
import com.docqa.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisModelConnectionPublisherTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONNECTION_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-22T10:15:42.010Z");
    private static final String SECRET = "sk-publisher-test-0123456789";
    private static final String KEY = "agent:model-connection:tenant:" + TENANT_ID;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private Cursor<String> cursor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelConnectionProperties properties = new ModelConnectionProperties();
    private RedisModelConnectionPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RedisModelConnectionPublisher(redisTemplate, properties, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /** 分发通道上只允许出现密文：明文 Key 不得出现在 Redis 的任何字段里。 */
    @Test
    void publishesCiphertextAndNeverThePlaintextKey() throws Exception {
        byte[] sealed = new CredentialCipher(key(), List.of())
                .seal(SECRET.getBytes(StandardCharsets.UTF_8), CredentialCipher.aad(CONNECTION_ID, TENANT_ID));

        publisher.publish(TENANT_ID, row(sealed, "3a990d9492e6"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), payload.capture());
        assertThat(payload.getValue()).doesNotContain(SECRET);
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.get("id").asText()).isEqualTo(CONNECTION_ID.toString());
        assertThat(json.get("baseUrl").asText()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(json.get("model").asText()).isEqualTo("deepseek-chat");
        assertThat(json.get("keyId").asText()).isEqualTo("3a990d9492e6");
        assertThat(json.get("revision").asLong()).isEqualTo(NOW.toEpochMilli());
        assertThat(Base64.getDecoder().decode(json.get("credentialB64").asText())).isEqualTo(sealed);
    }

    @Test
    void publishesNullCredentialForEndpointsThatNeedNoKey() throws Exception {
        publisher.publish(TENANT_ID, row(null, null));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.get("credentialB64").isNull()).isTrue();
        assertThat(json.get("keyId").isNull()).isTrue();
    }

    /** 没有生效连接就删键，让数据面回退到 env provider，而不是留一份过期配置。 */
    @Test
    void deletesTheKeyWhenNoConnectionIsActive() {
        publisher.publish(TENANT_ID, null);

        verify(redisTemplate).delete(KEY);
        verify(valueOperations, never()).set(any(), any());
    }

    @Test
    void republishWritesEveryActiveRowAndRemovesStaleKeys() {
        String staleKey = "agent:model-connection:tenant:" + OTHER_TENANT_ID;
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(KEY, staleKey);

        publisher.republishAll(List.of(row(null, null)));

        verify(valueOperations).set(eq(KEY), any(String.class));
        verify(redisTemplate).delete(staleKey);
        verify(redisTemplate, never()).delete(KEY);
    }

    /** Redis 写失败不能把已提交的库内变更回滚掉：MySQL 才是事实来源。 */
    @Test
    void swallowsRedisFailuresSoTheCommittedWriteStands() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        publisher.publish(TENANT_ID, row(null, null));
    }

    private static String key() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    private static ModelConnectionRow row(byte[] credential, String keyId) {
        return new ModelConnectionRow(CONNECTION_ID, TENANT_ID, "DeepSeek", "deepseek",
                "https://api.deepseek.com/v1", "deepseek-chat", true, true, credential, keyId,
                "OK", NOW, NOW, NOW, null);
    }
}
