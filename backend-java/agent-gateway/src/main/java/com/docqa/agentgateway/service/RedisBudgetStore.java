package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.AgentProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisBudgetStore implements BudgetStore {

    private final StringRedisTemplate redisTemplate;
    private final AgentProperties properties;

    public RedisBudgetStore(StringRedisTemplate redisTemplate, AgentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void initialize(String traceId, int tokenBudget, int maxToolCalls, int maxRounds, Instant deadlineAt) {
        String key = key(traceId);
        redisTemplate.opsForHash().putAll(key, Map.of(
                "tokensUsed", "0",
                "toolCallsMade", "0",
                "roundsUsed", "0",
                "tokenBudget", String.valueOf(tokenBudget),
                "maxToolCalls", String.valueOf(maxToolCalls),
                "maxRounds", String.valueOf(maxRounds),
                "deadlineAt", deadlineAt.toString()));
        redisTemplate.expire(key, properties.getBudgetTtl());
    }

    @Override
    public Optional<BudgetSnapshot> snapshot(String traceId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key(traceId));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BudgetSnapshot(
                    intValue(entries, "tokensUsed"),
                    intValue(entries, "toolCallsMade"),
                    intValue(entries, "roundsUsed"),
                    intValue(entries, "tokenBudget"),
                    intValue(entries, "maxToolCalls"),
                    intValue(entries, "maxRounds"),
                    Instant.parse(stringValue(entries, "deadlineAt"))));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private String key(String traceId) {
        return "agent:budget:" + traceId;
    }

    private int intValue(Map<Object, Object> entries, String field) {
        return Integer.parseInt(stringValue(entries, field));
    }

    private String stringValue(Map<Object, Object> entries, String field) {
        Object value = entries.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Missing budget field: " + field);
        }
        return value.toString();
    }
}
