package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.ModelConnectionProperties;
import com.docqa.agentgateway.repository.ModelConnectionRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 把生效连接发布到 Redis，供 Python 数据面按请求取用。
 *
 * <p>**只发密文**：{@code credentialB64} 是 AES-256-GCM 密文的 base64，AAD 绑定
 * {@code connectionId|tenantId}，Python 用同一把主密钥本地解密。明文 Key 不过这条通道，
 * 也不进内部 ask HTTP（dev 形态下那条通道没有内部鉴权）。
 *
 * <p>MySQL 是唯一事实来源，Redis 只是分发缓存：写失败不回滚已提交的库内变更，
 * 启动期全量重发布负责收敛。键里不带任何 PII，只有租户 UUID。
 */
@Component
public class RedisModelConnectionPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisModelConnectionPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ModelConnectionProperties properties;
    private final ObjectMapper objectMapper;

    public RedisModelConnectionPublisher(
            StringRedisTemplate redisTemplate,
            ModelConnectionProperties properties,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String key(UUID tenantId) {
        return properties.getKeyPrefix() + tenantId;
    }

    /** active 为 null 表示该租户当前没有生效连接：删键，让数据面回退到 env 配置的 provider。 */
    public void publish(UUID tenantId, ModelConnectionRow active) {
        String key = key(tenantId);
        try {
            if (active == null) {
                redisTemplate.delete(key);
                return;
            }
            redisTemplate.opsForValue().set(key, serialize(active));
        } catch (RuntimeException ex) {
            // 库内变更已提交，分发失败只让数据面暂时用旧配置；下次写或网关重启会收敛。
            LOGGER.warn("Model connection publish failed: tenantId={}, connectionId={}",
                    tenantId, active == null ? null : active.id());
        }
    }

    /**
     * 启动期全量重发布：写入所有生效连接，并清掉前缀下已无对应生效连接的残留键。
     *
     * <p>残留键只可能来自库被外部回滚/恢复（运行期每次生效变更都会同步删改键）。
     * 不清的话数据面会继续用一份库里已经不存在的配置，所以这里用 SCAN 对账一次；
     * 不用 KEYS，避免在租户多时阻塞 Redis。
     */
    public void republishAll(List<ModelConnectionRow> activeRows) {
        Set<String> expectedKeys = new HashSet<>();
        for (ModelConnectionRow row : activeRows) {
            publish(row.tenantId(), row);
            expectedKeys.add(key(row.tenantId()));
        }
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(properties.getKeyPrefix() + "*").count(100).build())) {
            while (cursor.hasNext()) {
                String existing = cursor.next();
                if (!expectedKeys.contains(existing)) {
                    redisTemplate.delete(existing);
                    LOGGER.info("Removed stale model connection key: {}", existing);
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("Model connection key reconciliation failed; published keys are still authoritative");
        }
    }

    private String serialize(ModelConnectionRow row) {
        try {
            PublishedConnection payload = new PublishedConnection(
                    row.id().toString(),
                    row.baseUrl(),
                    row.model(),
                    row.hasCredential() ? Base64.getEncoder().encodeToString(row.credential()) : null,
                    row.credentialKeyId(),
                    row.updatedAt().toEpochMilli());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("model connection serialization failed", ex);
        }
    }

    /** 与 Python 侧解析的字段名逐字一致；改动必须两侧同步。 */
    private record PublishedConnection(
            String id,
            String baseUrl,
            String model,
            String credentialB64,
            String keyId,
            long revision) {
    }
}
