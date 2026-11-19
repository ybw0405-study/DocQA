package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.dto.AiQuota;
import com.docqa.agentgateway.repository.AiQuotaRepository;
import com.docqa.common.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuotaGuardTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Mock
    private AiQuotaRepository quotaRepository;
    @Mock
    private QuotaUsageStore usageStore;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void countsEveryQuestionEvenWithoutAnOverride() {
        when(quotaRepository.findByUserId(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        when(usageStore.tryConsume(USER_ID, DAY, null, null)).thenReturn(true);
        QuotaGuard guard = guardAt(NOW);

        guard.reserve(USER_ID);

        verify(usageStore).tryConsume(USER_ID, DAY, null, null);
    }

    @Test
    void forwardsTheConfiguredLimitsToTheStore() {
        when(quotaRepository.findByUserId(TENANT_ID, USER_ID)).thenReturn(Optional.of(quota(30L, 60_000L)));
        when(usageStore.tryConsume(USER_ID, DAY, 30L, 60_000L)).thenReturn(true);
        QuotaGuard guard = guardAt(NOW);

        guard.reserve(USER_ID);

        verify(usageStore).tryConsume(USER_ID, DAY, 30L, 60_000L);
    }

    @Test
    void rejectsWithRetryAfterUntilTheNextUtcMidnight() {
        when(quotaRepository.findByUserId(TENANT_ID, USER_ID)).thenReturn(Optional.of(quota(1L, null)));
        when(usageStore.tryConsume(USER_ID, DAY, 1L, null)).thenReturn(false);
        QuotaGuard guard = guardAt(Instant.parse("2026-11-03T23:59:00Z"));

        assertThatThrownBy(() -> guard.reserve(USER_ID))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(ex -> {
                    QuotaExceededException quotaExceeded = (QuotaExceededException) ex;
                    assertThat(quotaExceeded.getRetryAfterSeconds()).isEqualTo(60);
                    assertThat(quotaExceeded.getDetail()).isEqualTo(QuotaExceededException.DETAIL);
                });
    }

    @Test
    void readsTheQuotaOnEveryReservation() {
        when(quotaRepository.findByUserId(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        when(usageStore.tryConsume(USER_ID, DAY, null, null)).thenReturn(true);
        QuotaGuard guard = guardAt(NOW);

        guard.reserve(USER_ID);
        guard.reserve(USER_ID);

        verify(quotaRepository, times(2)).findByUserId(TENANT_ID, USER_ID);
    }

    @Test
    void recordsTheActualTokenUsage() {
        QuotaGuard guard = guardAt(NOW);

        guard.recordTokens(USER_ID, 120L);

        verify(usageStore).addTokens(USER_ID, DAY, 120L);
    }

    @Test
    void skipsNonPositiveTokenUsage() {
        QuotaGuard guard = guardAt(NOW);

        guard.recordTokens(USER_ID, 0L);
        guard.recordTokens(USER_ID, -5L);

        verify(usageStore, never()).addTokens(any(), any(), anyLong());
    }

    @Test
    void meteringFailuresDoNotDiscardAnAlreadyProducedAnswer() {
        doThrow(new IllegalStateException("redis down")).when(usageStore).addTokens(any(), any(), anyLong());
        QuotaGuard guard = guardAt(NOW);

        assertThatCode(() -> guard.recordTokens(USER_ID, 120L)).doesNotThrowAnyException();
    }

    private QuotaGuard guardAt(Instant instant) {
        return new QuotaGuard(quotaRepository, usageStore, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static AiQuota quota(Long questionLimit, Long tokenLimit) {
        return new AiQuota(
                USER_ID, "zhang.wei", questionLimit, tokenLimit, NOW, ADMIN_ID);
    }
}
