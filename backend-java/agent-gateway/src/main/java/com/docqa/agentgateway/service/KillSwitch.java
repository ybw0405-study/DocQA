package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class KillSwitch {

    public static final String KEY = "agent:kill-switch";

    private static final Logger LOGGER = LoggerFactory.getLogger(KillSwitch.class);

    private final StringRedisTemplate redisTemplate;
    private final AgentProperties properties;

    private volatile boolean cachedActive;
    private volatile long cacheExpiresAtNanos;

    public KillSwitch(StringRedisTemplate redisTemplate, AgentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public boolean isActive() {
        long now = System.nanoTime();
        if (now < cacheExpiresAtNanos) {
            return cachedActive;
        }
        boolean active = readKillSwitch();
        cachedActive = active;
        cacheExpiresAtNanos = now + properties.getKillSwitchCacheTtl().toNanos();
        return active;
    }

    /** 管理写入成功后同步本机缓存，避免同一实例在缓存窗口内继续按旧状态执行。 */
    public void refresh(boolean active) {
        this.cachedActive = active;
        this.cacheExpiresAtNanos = System.nanoTime() + properties.getKillSwitchCacheTtl().toNanos();
    }

    private boolean readKillSwitch() {
        try {
            String value = redisTemplate.opsForValue().get(KEY);
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        } catch (RuntimeException ex) {
            LOGGER.warn("Kill switch lookup failed; AI calls remain enabled");
            return false;
        }
    }
}
