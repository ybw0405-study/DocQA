package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.config.AgentProperties;
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
class KillSwitchTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        // 共享基础设施 stub：纯静态键名用例（tenantKeyIsNamespacedByTenantId）不触碰 Redis，
        // 用 lenient 避免 Mockito 严格模式把「全体共用」的连接桩误判为冗余。
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void treatsOneAsActive() {
        when(valueOperations.get(KillSwitch.KEY)).thenReturn("1");

        assertThat(new KillSwitch(redisTemplate, properties).isActive()).isTrue();
    }

    @Test
    void treatsTrueAsActiveIgnoringCase() {
        when(valueOperations.get(KillSwitch.KEY)).thenReturn("TRUE");

        assertThat(new KillSwitch(redisTemplate, properties).isActive()).isTrue();
    }

    @Test
    void treatsMissingValueAsInactive() {
        when(valueOperations.get(KillSwitch.KEY)).thenReturn(null);

        assertThat(new KillSwitch(redisTemplate, properties).isActive()).isFalse();
    }

    @Test
    void cachesResultForConfiguredTtl() {
        when(valueOperations.get(KillSwitch.KEY)).thenReturn("1", "0");
        KillSwitch killSwitch = new KillSwitch(redisTemplate, properties);

        assertThat(killSwitch.isActive()).isTrue();
        assertThat(killSwitch.isActive()).isTrue();
        verify(valueOperations, times(1)).get(KillSwitch.KEY);
    }

    @Test
    void rereadsWhenCacheTtlIsZero() {
        properties.setKillSwitchCacheTtl(Duration.ZERO);
        when(valueOperations.get(KillSwitch.KEY)).thenReturn("1", "0");
        KillSwitch killSwitch = new KillSwitch(redisTemplate, properties);

        assertThat(killSwitch.isActive()).isTrue();
        assertThat(killSwitch.isActive()).isFalse();
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        when(valueOperations.get(KillSwitch.KEY))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(new KillSwitch(redisTemplate, properties).isActive()).isFalse();
    }

    @Test
    void tenantKeyIsNamespacedByTenantId() {
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

        assertThat(KillSwitch.tenantKey(tenant))
                .isEqualTo("agent:kill-switch:tenant:" + tenant);
        assertThat(KillSwitch.tenantMetaKey(tenant))
                .isEqualTo("agent:kill-switch:tenant:" + tenant + ":meta");
    }

    @Test
    void tenantSwitchIsActiveWhenGlobalSwitchIsOn() {
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
        when(valueOperations.get(KillSwitch.KEY)).thenReturn("1");

        assertThat(new KillSwitch(redisTemplate, properties).isActiveForTenant(tenant)).isTrue();
        // 全局已停，不必再读租户键。
        verify(valueOperations, never()).get(KillSwitch.tenantKey(tenant));
    }

    @Test
    void tenantSwitchReadsItsOwnKeyWhenGlobalIsOff() {
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
        when(valueOperations.get(KillSwitch.KEY)).thenReturn(null);
        when(valueOperations.get(KillSwitch.tenantKey(tenant))).thenReturn("true");

        assertThat(new KillSwitch(redisTemplate, properties).isActiveForTenant(tenant)).isTrue();
    }

    @Test
    void tenantSwitchFailsOpenWhenRedisIsUnavailable() {
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
        when(valueOperations.get(KillSwitch.KEY)).thenReturn(null);
        when(valueOperations.get(KillSwitch.tenantKey(tenant)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(new KillSwitch(redisTemplate, properties).isActiveForTenant(tenant)).isFalse();
    }
}
