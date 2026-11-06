package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.dto.UsageDayPoint;
import com.docqa.agentgateway.dto.UsageStatusCount;
import com.docqa.agentgateway.dto.UsageSummary;
import com.docqa.agentgateway.dto.UsageTopUser;
import com.docqa.agentgateway.dto.UsageTotals;
import com.docqa.agentgateway.repository.AiUsageRepository;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAiUsageServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Instant FROM = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-03T09:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Mock
    private AiUsageRepository usageRepository;

    private AdminAiUsageService service;

    @BeforeEach
    void setUp() {
        service = new AdminAiUsageService(usageRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void defaultsToTheLastTwentyFourHoursAndTenTopUsers() {
        stubEmpty();

        UsageSummary summary = service.summarize(null, null, null, 10);

        assertThat(summary.from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(summary.to()).isEqualTo(NOW);
        assertThat(summary.bucket()).isEqualTo("day");
        verify(usageRepository).totals(NOW.minus(Duration.ofHours(24)), NOW);
        verify(usageRepository).byDay(NOW.minus(Duration.ofHours(24)), NOW);
        verify(usageRepository).byStatus(NOW.minus(Duration.ofHours(24)), NOW);
        verify(usageRepository).topUsers(NOW.minus(Duration.ofHours(24)), NOW, 10);
    }

    @Test
    void appliesAnExplicitWindowAndTopUserCount() {
        stubEmpty();

        UsageSummary summary = service.summarize(
                FROM.toString(), TO.toString(), "day", 25);

        assertThat(summary.from()).isEqualTo(FROM);
        assertThat(summary.to()).isEqualTo(TO);
        verify(usageRepository).topUsers(FROM, TO, 25);
    }

    @Test
    void treatsABlankBucketAsTheDayBucket() {
        stubEmpty();

        assertThat(service.summarize(null, null, " ", 10).bucket()).isEqualTo("day");
    }

    @Test
    void sortsStatusesInTheContractOrder() {
        when(usageRepository.totals(any(), any())).thenReturn(totals());
        when(usageRepository.byDay(any(), any())).thenReturn(List.of());
        when(usageRepository.byStatus(any(), any())).thenReturn(List.of(
                new UsageStatusCount("FAILED", 9),
                new UsageStatusCount("SUCCESS", 113),
                new UsageStatusCount("BUDGET_EXHAUSTED", 6),
                new UsageStatusCount("RUNNING", 1)));
        when(usageRepository.topUsers(any(), any(), anyInt())).thenReturn(List.of());

        UsageSummary summary = service.summarize(null, null, null, 10);

        assertThat(summary.byStatus())
                .extracting(UsageStatusCount::status)
                .containsExactly("RUNNING", "SUCCESS", "BUDGET_EXHAUSTED", "FAILED");
    }

    @Test
    void keepsUnknownStatusesAfterTheKnownOnes() {
        when(usageRepository.totals(any(), any())).thenReturn(totals());
        when(usageRepository.byDay(any(), any())).thenReturn(List.of());
        when(usageRepository.byStatus(any(), any())).thenReturn(List.of(
                new UsageStatusCount("ZZZ", 1),
                new UsageStatusCount("SUCCESS", 2)));
        when(usageRepository.topUsers(any(), any(), anyInt())).thenReturn(List.of());

        assertThat(service.summarize(null, null, null, 10).byStatus())
                .extracting(UsageStatusCount::status)
                .containsExactly("SUCCESS", "ZZZ");
    }

    @Test
    void preservesTheNullAverageWhenTheWindowIsEmpty() {
        UsageTotals empty = new UsageTotals(0, 0, 0, 0, 0, 0, 0, 0, null);
        when(usageRepository.totals(any(), any())).thenReturn(empty);
        when(usageRepository.byDay(any(), any())).thenReturn(List.of());
        when(usageRepository.byStatus(any(), any())).thenReturn(List.of());
        when(usageRepository.topUsers(any(), any(), anyInt())).thenReturn(List.of());

        UsageSummary summary = service.summarize(null, null, null, 10);

        assertThat(summary.totals().avgLatencyMs()).isNull();
        assertThat(summary.byDay()).isEmpty();
        assertThat(summary.topUsers()).isEmpty();
    }

    @Test
    void returnsTheAggregatedRowsUnchanged() {
        UsageDayPoint day = new UsageDayPoint("2026-11-02", 61, 198_402, 5);
        UsageTopUser user = new UsageTopUser(USER_ID, "zhang.wei", 44, 142_731);
        when(usageRepository.totals(any(), any())).thenReturn(totals());
        when(usageRepository.byDay(any(), any())).thenReturn(List.of(day));
        when(usageRepository.byStatus(any(), any())).thenReturn(List.of(new UsageStatusCount("SUCCESS", 113)));
        when(usageRepository.topUsers(any(), any(), anyInt())).thenReturn(List.of(user));

        UsageSummary summary = service.summarize(null, null, null, 10);

        assertThat(summary.byDay()).containsExactly(day);
        assertThat(summary.topUsers()).containsExactly(user);
        assertThat(summary.totals().tokens()).isEqualTo(412_882);
    }

    @Test
    void rejectsBucketsOtherThanDay() {
        assertValidation("bucket", () -> service.summarize(null, null, "hour", 10));
    }

    @Test
    void rejectsTopUserCountsOutsideOneToFifty() {
        assertValidation("topUsers", () -> service.summarize(null, null, null, 0));
        assertValidation("topUsers", () -> service.summarize(null, null, null, 51));
    }

    @Test
    void rejectsWindowsLongerThanThirtyOneDays() {
        assertValidation("from", () -> service.summarize(
                "2026-09-01T00:00:00Z", "2026-11-03T00:00:00Z", null, 10));
    }

    @Test
    void rejectsUnparsableTimestamps() {
        assertValidation("to", () -> service.summarize(null, "not-a-time", null, 10));
    }

    private void stubEmpty() {
        when(usageRepository.totals(any(), any())).thenReturn(totals());
        when(usageRepository.byDay(any(), any())).thenReturn(List.of());
        when(usageRepository.byStatus(any(), any())).thenReturn(List.of());
        when(usageRepository.topUsers(any(), any(), anyInt())).thenReturn(List.of());
    }

    private static UsageTotals totals() {
        return new UsageTotals(128, 113, 6, 9, 412_882, 74, 301, 17, 2140.5);
    }

    private static void assertValidation(String field, ThrowingCallable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001);
                    assertThat(ex.getFieldViolations())
                            .extracting(FieldViolation::field)
                            .contains(field);
                });
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void run();
    }
}
