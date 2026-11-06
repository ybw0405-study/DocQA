package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.dto.AdminAiControls;
import com.docqa.agentgateway.dto.ExperimentBudgetState;
import com.docqa.agentgateway.dto.KillSwitchState;
import com.docqa.agentgateway.repository.AdminAuditEntry;
import com.docqa.agentgateway.repository.AdminAuditRepository;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAiControlsServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private KillSwitch killSwitch;
    @Mock
    private AdminControlsStore controlsStore;
    @Mock
    private AdminAuditRepository auditRepository;

    private AdminAiControlsService service;

    @BeforeEach
    void setUp() {
        service = new AdminAiControlsService(
                killSwitch,
                controlsStore,
                auditRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void readsKillSwitchMetadataAndPublishedLimits() {
        when(killSwitch.isActive()).thenReturn(true);
        when(controlsStore.killSwitchMeta())
                .thenReturn(Optional.of(new KillSwitchMeta(NOW, ADMIN_ID)));
        when(controlsStore.experimentBudget())
                .thenReturn(new ExperimentBudgetView(12, 345, 100L, 200_000L));

        AdminAiControls controls = service.getControls();

        assertThat(controls.killSwitch()).isEqualTo(new KillSwitchState(true, NOW, ADMIN_ID));
        assertThat(controls.experimentBudget())
                .isEqualTo(new ExperimentBudgetState(12, 345, 100L, 200_000L, true));
    }

    @Test
    void omitsMetadataAndLimitsWhenTheyWereNeverPublished() {
        when(killSwitch.isActive()).thenReturn(false);
        when(controlsStore.killSwitchMeta()).thenReturn(Optional.empty());
        when(controlsStore.experimentBudget()).thenReturn(new ExperimentBudgetView(0, 0, null, null));

        AdminAiControls controls = service.getControls();

        assertThat(controls.killSwitch()).isEqualTo(new KillSwitchState(false, null, null));
        assertThat(controls.experimentBudget())
                .isEqualTo(new ExperimentBudgetState(0, 0, null, null, false));
    }

    @Test
    void enablingWritesRefreshesThenAuditsWithWarn() {
        stubAppliedState(true);

        service.updateKillSwitch(ADMIN_ID, true);

        InOrder order = inOrder(controlsStore, killSwitch, auditRepository);
        order.verify(controlsStore).writeKillSwitch(true, ADMIN_ID, NOW);
        order.verify(killSwitch).refresh(true);
        ArgumentCaptor<AdminAuditEntry> captor = ArgumentCaptor.forClass(AdminAuditEntry.class);
        order.verify(auditRepository).insert(captor.capture());

        AdminAuditEntry entry = captor.getValue();
        assertThat(entry.id()).isNotNull();
        assertThat(entry.traceId()).matches("^[0-9a-f]{32}$");
        assertThat(entry.userId()).isEqualTo(ADMIN_ID);
        assertThat(entry.eventType()).isEqualTo(AdminAiControlsService.EVENT_KILL_SWITCH_ENABLED);
        assertThat(entry.severity()).isEqualTo("WARN");
        assertThat(entry.payload()).isEqualTo(Map.of());
        assertThat(entry.occurredAt()).isEqualTo(NOW);
        assertThat(entry.ingestedAt()).isEqualTo(NOW);
    }

    @Test
    void disablingWritesRefreshesThenAuditsWithInfo() {
        stubAppliedState(false);

        service.updateKillSwitch(ADMIN_ID, false);

        verify(controlsStore).writeKillSwitch(false, ADMIN_ID, NOW);
        verify(killSwitch).refresh(false);
        ArgumentCaptor<AdminAuditEntry> captor = ArgumentCaptor.forClass(AdminAuditEntry.class);
        verify(auditRepository).insert(captor.capture());
        assertThat(captor.getValue().eventType())
                .isEqualTo(AdminAiControlsService.EVENT_KILL_SWITCH_DISABLED);
        assertThat(captor.getValue().severity()).isEqualTo("INFO");
    }

    @Test
    void auditFailureReportsAnInternalErrorAfterTheStateWasApplied() {
        doThrow(new IllegalStateException("audit database unavailable"))
                .when(auditRepository).insert(any());

        BusinessException failure = catchThrowableOfType(
                () -> service.updateKillSwitch(ADMIN_ID, true),
                BusinessException.class);

        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.COM_INTERNAL_001);
        assertThat(failure.getDetail()).contains("已变更");
        verify(controlsStore).writeKillSwitch(true, ADMIN_ID, NOW);
        verify(killSwitch).refresh(true);
        verify(auditRepository).insert(any());
    }

    private void stubAppliedState(boolean active) {
        when(killSwitch.isActive()).thenReturn(active);
        when(controlsStore.killSwitchMeta())
                .thenReturn(Optional.of(new KillSwitchMeta(NOW, ADMIN_ID)));
        when(controlsStore.experimentBudget())
                .thenReturn(new ExperimentBudgetView(1, 2, null, null));
    }
}
