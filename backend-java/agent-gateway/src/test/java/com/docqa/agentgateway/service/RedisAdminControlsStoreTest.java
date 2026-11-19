package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.config.AdminProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisAdminControlsStoreTest {

    private static final String META_KEY = "agent:kill-switch:meta";
    private static final String BUDGET_KEY = "agent:budget:session-tokens";
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String NOW_MILLIS = "1767225600000";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private RedisAdminControlsStore store;

    @BeforeEach
    void setUp() {
        AdminProperties properties = new AdminProperties();
        properties.setKillSwitchMetaKey(META_KEY);
        properties.setExperimentBudgetKey(BUDGET_KEY);
        store = new RedisAdminControlsStore(redisTemplate, properties);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void readsKillSwitchMetadata() {
        when(hashOperations.entries(META_KEY))
                .thenReturn(Map.<Object, Object>of("updatedAt", NOW_MILLIS, "updatedBy", ADMIN_ID.toString()));

        assertThat(store.killSwitchMeta())
                .contains(new KillSwitchMeta(Instant.ofEpochMilli(1767225600000L), ADMIN_ID));
    }

    @Test
    void reportsAbsentMetadataWhenHashIsEmpty() {
        when(hashOperations.entries(META_KEY)).thenReturn(Map.of());

        assertThat(store.killSwitchMeta()).isEmpty();
    }

    @Test
    void reportsAbsentMetadataWhenItIsMalformed() {
        when(hashOperations.entries(META_KEY))
                .thenReturn(Map.<Object, Object>of("updatedAt", "not-a-number", "updatedBy", ADMIN_ID.toString()));

        assertThat(store.killSwitchMeta()).isEmpty();
    }

    @Test
    void reportsAbsentMetadataWhenRedisFails() {
        when(hashOperations.entries(META_KEY)).thenThrow(new IllegalStateException("redis down"));

        assertThat(store.killSwitchMeta()).isEmpty();
    }

    @Test
    void enablingSetsTheFlagAndRecordsMetadata() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        store.writeKillSwitch(true, ADMIN_ID, NOW);

        verify(valueOperations).set(KillSwitch.KEY, "1");
        verify(redisTemplate, never()).delete(anyString());
        verify(hashOperations).putAll(META_KEY, Map.of(
                "updatedAt", NOW_MILLIS,
                "updatedBy", ADMIN_ID.toString()));
    }

    @Test
    void disablingDeletesTheFlagAndKeepsMetadata() {
        store.writeKillSwitch(false, ADMIN_ID, NOW);

        verify(redisTemplate).delete(KillSwitch.KEY);
        verify(redisTemplate, never()).opsForValue();
        verify(hashOperations).putAll(META_KEY, Map.of(
                "updatedAt", NOW_MILLIS,
                "updatedBy", ADMIN_ID.toString()));
    }

    @Test
    void metadataWriteFailureDoesNotFailTheAppliedSwitch() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new IllegalStateException("redis down")).when(hashOperations).putAll(eq(META_KEY), anyMap());

        assertThatCode(() -> store.writeKillSwitch(true, ADMIN_ID, NOW)).doesNotThrowAnyException();

        verify(valueOperations).set(KillSwitch.KEY, "1");
    }

    @Test
    void tenantKillSwitchUsesItsOwnKeyAndMetaKey() {
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(hashOperations.entries(KillSwitch.tenantMetaKey(tenant)))
                .thenReturn(Map.<Object, Object>of("updatedAt", NOW_MILLIS, "updatedBy", ADMIN_ID.toString()));

        store.writeTenantKillSwitch(tenant, true, ADMIN_ID, NOW);

        verify(valueOperations).set(KillSwitch.tenantKey(tenant), "1");
        verify(redisTemplate, never()).delete(KillSwitch.KEY);
        assertThat(store.tenantKillSwitchMeta(tenant))
                .contains(new KillSwitchMeta(Instant.ofEpochMilli(1767225600000L), ADMIN_ID));
    }

    @Test
    void disablingTenantSwitchDeletesOnlyTheTenantKey() {
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

        store.writeTenantKillSwitch(tenant, false, ADMIN_ID, NOW);

        verify(redisTemplate).delete(KillSwitch.tenantKey(tenant));
        verify(redisTemplate, never()).delete(KillSwitch.KEY);
    }

    @Test
    void readsCountersWithoutLimits() {
        when(hashOperations.entries(BUDGET_KEY))
                .thenReturn(Map.<Object, Object>of("callsUsed", "12", "tokensUsed", "345"));

        ExperimentBudgetView view = store.experimentBudget();

        assertThat(view).isEqualTo(new ExperimentBudgetView(12, 345, null, null));
        assertThat(view.limitsKnown()).isFalse();
    }

    @Test
    void readsCountersWithPublishedLimits() {
        when(hashOperations.entries(BUDGET_KEY)).thenReturn(Map.<Object, Object>of(
                "callsUsed", "12",
                "tokensUsed", "345",
                "callLimit", "100",
                "tokenLimit", "200000"));

        ExperimentBudgetView view = store.experimentBudget();

        assertThat(view).isEqualTo(new ExperimentBudgetView(12, 345, 100L, 200_000L));
        assertThat(view.limitsKnown()).isTrue();
    }

    @Test
    void treatsMalformedEntriesAsAbsent() {
        when(hashOperations.entries(BUDGET_KEY)).thenReturn(Map.<Object, Object>of(
                "callsUsed", "not-a-number",
                "tokensUsed", "7",
                "callLimit", "not-a-number"));

        ExperimentBudgetView view = store.experimentBudget();

        assertThat(view).isEqualTo(new ExperimentBudgetView(0, 7, null, null));
        assertThat(Optional.ofNullable(view.callLimit())).isEmpty();
    }
}
