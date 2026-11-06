package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.dto.AuditEventItem;
import com.docqa.agentgateway.dto.AuditEventListData;
import com.docqa.agentgateway.repository.AdminAuditQueryRepository;
import com.docqa.agentgateway.repository.AuditQueryFilter;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAuditQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Mock
    private AdminAuditQueryRepository auditRepository;

    private AdminAuditQueryService service;

    @BeforeEach
    void setUp() {
        service = new AdminAuditQueryService(auditRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void usesTheDefaultTwentyFourHourWindowWhenNoFilterIsGiven() {
        when(auditRepository.findPage(any(), isNull(), isNull(), anyInt())).thenReturn(List.of());

        service.list(null, null, null, null, null, 100, null);

        AuditQueryFilter filter = capturedFilter();
        assertThat(filter.from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(filter.to()).isEqualTo(NOW);
        verify(auditRepository).findPage(any(), isNull(), isNull(), org.mockito.ArgumentMatchers.eq(101));
    }

    @Test
    void leavesTheWindowOpenWhenAFilterIsGiven() {
        when(auditRepository.findPage(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.list(TRACE_ID, null, null, null, null, 100, null);

        AuditQueryFilter filter = capturedFilter();
        assertThat(filter.traceId()).isEqualTo(TRACE_ID);
        assertThat(filter.from()).isNull();
        assertThat(filter.to()).isNull();
    }

    @Test
    void appliesAnExplicitWindowToFilteredQueries() {
        when(auditRepository.findPage(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.list(
                null,
                USER_ID.toString(),
                "RAG_RETRIEVED",
                "2026-10-05T00:00:00Z",
                "2026-11-03T00:00:00Z",
                100,
                null);

        AuditQueryFilter filter = capturedFilter();
        assertThat(filter.userId()).isEqualTo(USER_ID);
        assertThat(filter.eventType()).isEqualTo("RAG_RETRIEVED");
        assertThat(filter.from()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
        assertThat(filter.to()).isEqualTo(Instant.parse("2026-11-03T00:00:00Z"));
    }

    @Test
    void forwardsTheCursorAsTheKeysetPosition() {
        when(auditRepository.findPage(any(), any(), any(), anyInt())).thenReturn(List.of());
        String cursor = KeysetCursor.of(NOW.minusSeconds(30), USER_ID).encode();

        service.list(TRACE_ID, null, null, null, null, 100, cursor);

        verify(auditRepository).findPage(
                any(AuditQueryFilter.class),
                org.mockito.ArgumentMatchers.eq(NOW.minusSeconds(30)),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(101));
    }

    @Test
    void buildsTheNextCursorFromTheLastVisibleRow() {
        when(auditRepository.findPage(any(), any(), any(), anyInt()))
                .thenReturn(items(101));

        AuditEventListData page = service.list(TRACE_ID, null, null, null, null, 100, null);

        assertThat(page.items()).hasSize(100);
        assertThat(page.limit()).isEqualTo(100);
        KeysetCursor next = KeysetCursor.decode(page.nextCursor()).orElseThrow();
        AuditEventItem last = page.items().get(99);
        assertThat(next.sortKey()).isEqualTo(last.occurredAt());
        assertThat(next.id()).isEqualTo(last.eventId());
    }

    @Test
    void omitsTheCursorWhenThePageIsExact() {
        when(auditRepository.findPage(any(), any(), any(), anyInt()))
                .thenReturn(items(100));

        AuditEventListData page = service.list(TRACE_ID, null, null, null, null, 100, null);

        assertThat(page.items()).hasSize(100);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void rejectsPagesOverFiveHundred() {
        assertValidation("limit", () -> service.list(null, null, null, null, null, 501, null));
    }

    @Test
    void rejectsWindowsLongerThanThirtyOneDays() {
        assertValidation("from", () -> service.list(
                null, null, null, "2026-09-01T00:00:00Z", "2026-11-03T00:00:00Z", 100, null));
    }

    @Test
    void rejectsInvertedWindows() {
        assertValidation("from", () -> service.list(
                null, null, null, "2026-11-03T00:00:00Z", "2026-11-02T00:00:00Z", 100, null));
    }

    @Test
    void rejectsAnUnparsableTimestamp() {
        assertValidation("from", () -> service.list(
                null, null, null, "yesterday", null, 100, null));
    }

    @Test
    void rejectsTraceIdsThatAreNotLowercaseHex() {
        assertValidation("traceId", () -> service.list(
                "4BF92F3577B34DA6A3CE929D0E0E4736", null, null, null, null, 100, null));
    }

    @Test
    void rejectsEventTypesOutsideTheContractPattern() {
        assertValidation("eventType", () -> service.list(
                null, null, "rag_retrieved", null, null, 100, null));
    }

    @Test
    void rejectsUserIdsThatAreNotUuids() {
        assertValidation("userId", () -> service.list(
                null, "not-a-uuid", null, null, null, 100, null));
    }

    @Test
    void rejectsCursorsThatWereNotIssuedByThisApi() {
        assertValidation("cursor", () -> service.list(
                null, null, null, null, null, 100, "not-a-cursor"));
    }

    @Test
    void treatsBlankFiltersAsAbsent() {
        when(auditRepository.findPage(any(), any(), any(), anyInt())).thenReturn(List.of());

        service.list("", "  ", "", " ", null, 100, " ");

        AuditQueryFilter filter = capturedFilter();
        assertThat(filter.traceId()).isNull();
        assertThat(filter.userId()).isNull();
        assertThat(filter.eventType()).isNull();
        assertThat(filter.from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
    }

    private AuditQueryFilter capturedFilter() {
        ArgumentCaptor<AuditQueryFilter> captor = ArgumentCaptor.forClass(AuditQueryFilter.class);
        verify(auditRepository).findPage(captor.capture(), any(), any(), anyInt());
        return captor.getValue();
    }

    private static List<AuditEventItem> items(int size) {
        List<AuditEventItem> rows = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            rows.add(new AuditEventItem(
                    UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", index)),
                    TRACE_ID,
                    USER_ID,
                    "RAG_RETRIEVED",
                    null,
                    "PYTHON",
                    "INFO",
                    Map.of(),
                    NOW.minusSeconds(index)));
        }
        return rows;
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
