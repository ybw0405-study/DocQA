package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.AgentProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class KillSwitch {

    public static final String KEY = "agent:kill-switch";

    /** 租户级开关键前缀：{@code KEY + ":tenant:" + tenantId}。全局键保留平台一键全停语义。 */
    public static final String TENANT_KEY_PREFIX = "agent:kill-switch:tenant:";

    public static String tenantKey(UUID tenantId) {
        return TENANT_KEY_PREFIX + tenantId;
    }

    public static String tenantMetaKey(UUID tenantId) {
        return TENANT_KEY_PREFIX + tenantId + ":meta";
    }

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

    /**
     * 租户级有效状态：全局一键全停 OR 该租户自己的开关。
     *
     * <p>真正的执行点在 Python agent（它按请求 tenant_id 做同样的 OR 判读）；本方法供管理面
     * 展示某租户当前是否被停。全局键沿用缓存，租户键每次直读 Redis——管理面是低频调用，
     * 且这样写入后能立刻看到结果，不必等缓存窗口。
     */
    public boolean isActiveForTenant(UUID tenantId) {
        if (isActive()) {
            return true;
        }
        try {
            String value = redisTemplate.opsForValue().get(tenantKey(tenantId));
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        } catch (RuntimeException ex) {
            LOGGER.warn("Tenant kill switch lookup failed; AI calls remain enabled");
            return false;
        }
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
