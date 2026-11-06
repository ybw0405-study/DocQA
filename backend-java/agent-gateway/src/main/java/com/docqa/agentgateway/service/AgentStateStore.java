package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.AgentProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class AgentStateStore {

    private final StringRedisTemplate redisTemplate;
    private final AgentProperties properties;

    public AgentStateStore(StringRedisTemplate redisTemplate, AgentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void initialize(String traceId) {
        redisTemplate.opsForValue().set(key(traceId), "RUNNING", properties.getStateTtl());
    }

    public void clear(String traceId) {
        redisTemplate.delete(key(traceId));
    }

    private String key(String traceId) {
        return "agent:state:" + traceId;
    }
}
