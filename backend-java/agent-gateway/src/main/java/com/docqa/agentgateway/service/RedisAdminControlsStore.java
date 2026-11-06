package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.AdminProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisAdminControlsStore implements AdminControlsStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisAdminControlsStore.class);

    private static final String FIELD_UPDATED_AT = "updatedAt";
    private static final String FIELD_UPDATED_BY = "updatedBy";
    private static final String FIELD_CALLS_USED = "callsUsed";
    private static final String FIELD_TOKENS_USED = "tokensUsed";
    private static final String FIELD_CALL_LIMIT = "callLimit";
    private static final String FIELD_TOKEN_LIMIT = "tokenLimit";

    private final StringRedisTemplate redisTemplate;
    private final AdminProperties properties;

    public RedisAdminControlsStore(StringRedisTemplate redisTemplate, AdminProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public Optional<KillSwitchMeta> killSwitchMeta() {
        Map<Object, Object> entries;
        try {
            entries = redisTemplate.opsForHash().entries(properties.getKillSwitchMetaKey());
        } catch (RuntimeException ex) {
            LOGGER.warn("Kill switch metadata lookup failed; reporting it as absent");
            return Optional.empty();
        }
        Object updatedAt = entries.get(FIELD_UPDATED_AT);
        Object updatedBy = entries.get(FIELD_UPDATED_BY);
        if (updatedAt == null || updatedBy == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new KillSwitchMeta(
                    Instant.ofEpochMilli(Long.parseLong(updatedAt.toString())),
                    UUID.fromString(updatedBy.toString())));
        } catch (RuntimeException ex) {
            LOGGER.warn("Kill switch metadata is malformed; reporting it as absent");
            return Optional.empty();
        }
    }

    @Override
    public void writeKillSwitch(boolean active, UUID updatedBy, Instant now) {
        if (active) {
            redisTemplate.opsForValue().set(KillSwitch.KEY, "1");
        } else {
            redisTemplate.delete(KillSwitch.KEY);
        }
        try {
            redisTemplate.opsForHash().putAll(properties.getKillSwitchMetaKey(), Map.of(
                    FIELD_UPDATED_AT, String.valueOf(now.toEpochMilli()),
                    FIELD_UPDATED_BY, updatedBy.toString()));
        } catch (RuntimeException ex) {
            // 主键才是状态事实；元数据写失败只影响展示，不回滚已生效的开关。
            LOGGER.warn("Kill switch metadata write failed; state is already applied");
        }
    }

    @Override
    public ExperimentBudgetView experimentBudget() {
        Map<Object, Object> entries =
                redisTemplate.opsForHash().entries(properties.getExperimentBudgetKey());
        return new ExperimentBudgetView(
                longValue(entries.get(FIELD_CALLS_USED)),
                longValue(entries.get(FIELD_TOKENS_USED)),
                optionalLong(entries.get(FIELD_CALL_LIMIT)),
                optionalLong(entries.get(FIELD_TOKEN_LIMIT)));
    }

    private long longValue(Object value) {
        try {
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private Long optionalLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
