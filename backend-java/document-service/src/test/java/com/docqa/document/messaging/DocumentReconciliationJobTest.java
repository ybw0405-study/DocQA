package com.docqa.document.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.document.config.DocumentProperties;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.domain.TenantDocument;
import com.docqa.document.repository.DocumentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentReconciliationJobTest {

    private static final UUID TENANT_ID = UUID.fromString("0192f3a4-a1b2-7c33-9d01-1234567890ab");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("0192f3a4-a1b2-7c33-9d01-1234567890ac");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentEventPublisher documentEventPublisher;

    private DocumentProperties properties;
    private DocumentReconciliationJob job;

    @BeforeEach
    void setUp() {
        properties = new DocumentProperties();
        job = new DocumentReconciliationJob(
                documentRepository,
                documentEventPublisher,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishFailureDoesNotConsumeRetryCount() {
        when(documentRepository.findStaleParsing(any(Instant.class), eq(50)))
                .thenReturn(List.of(tenantDocument(document(0))));
        doThrow(new IllegalStateException("mq down"))
                .when(documentEventPublisher)
                .publishParseRequested(any(ParseRequestedEvent.class));

        job.reconcileStaleParsing();

        verify(documentRepository, never()).incrementRetryAndMarkParsing(eq(TENANT_ID), any(), any());
    }

    @Test
    void successfulRepublishIncrementsRetryCountInTheRowsTenant() {
        when(documentRepository.findStaleParsing(any(Instant.class), eq(50)))
                .thenReturn(List.of(tenantDocument(document(0))));
        when(documentRepository.incrementRetryAndMarkParsing(TENANT_ID, DOCUMENT_ID, NOW)).thenReturn(1);

        job.reconcileStaleParsing();

        ArgumentCaptor<ParseRequestedEvent> event = ArgumentCaptor.forClass(ParseRequestedEvent.class);
        verify(documentEventPublisher).publishParseRequested(event.capture());
        assertThat(event.getValue().retryCount()).isEqualTo(1);
        verify(documentRepository).incrementRetryAndMarkParsing(TENANT_ID, DOCUMENT_ID, NOW);
    }

    @Test
    void staleParsingScansEveryTenantAndWritesInEachOwnTenant() {
        when(documentRepository.findStaleParsing(any(Instant.class), eq(50)))
                .thenReturn(List.of(
                        tenantDocument(TENANT_ID, document(0)),
                        tenantDocument(OTHER_TENANT_ID, document(0))));
        when(documentRepository.incrementRetryAndMarkParsing(any(), eq(DOCUMENT_ID), eq(NOW))).thenReturn(1);

        job.reconcileStaleParsing();

        verify(documentRepository).incrementRetryAndMarkParsing(TENANT_ID, DOCUMENT_ID, NOW);
        verify(documentRepository).incrementRetryAndMarkParsing(OTHER_TENANT_ID, DOCUMENT_ID, NOW);
        verify(documentEventPublisher, times(2)).publishParseRequested(any(ParseRequestedEvent.class));
    }

    @Test
    void exhaustedDocumentIsMarkedDeadWithoutPublishing() {
        when(documentRepository.findStaleParsing(any(Instant.class), eq(50)))
                .thenReturn(List.of(tenantDocument(document(3))));

        job.reconcileStaleParsing();

        verify(documentRepository).markDead(eq(TENANT_ID), eq(DOCUMENT_ID), eq("COM-INTERNAL-001"), any());
        verify(documentEventPublisher, never()).publishParseRequested(any(ParseRequestedEvent.class));
    }

    @Test
    void staleParsingScanUsesTheConfiguredThreshold() {
        properties.setStaleThreshold(Duration.ofMinutes(10));

        job.reconcileStaleParsing();

        verify(documentRepository).findStaleParsing(NOW.minus(Duration.ofMinutes(10)), 50);
    }

    @Test
    void timedOutRebuildAbortsOnlyThePendingVersionInTheRowsTenant() {
        when(documentRepository.findStaleReindex(any(Instant.class), eq(50)))
                .thenReturn(List.of(tenantDocument(rebuilding())));

        job.abortStaleReindex();

        verify(documentRepository).abortReindex(TENANT_ID,
                DOCUMENT_ID,
                "v2",
                "COM-INTERNAL-001",
                "重建索引超时，已保留原索引版本，可稍后再次发起重建。");
        verify(documentRepository, never()).markDead(eq(TENANT_ID), any(), any(), any());
        verify(documentRepository, never()).incrementRetryAndMarkParsing(eq(TENANT_ID), any(), any());
        verifyNoInteractions(documentEventPublisher);
    }

    @Test
    void rebuildTimeoutAlsoAbortsInOtherTenants() {
        when(documentRepository.findStaleReindex(any(Instant.class), eq(50)))
                .thenReturn(List.of(
                        tenantDocument(TENANT_ID, rebuilding()),
                        tenantDocument(OTHER_TENANT_ID, rebuilding())));

        job.abortStaleReindex();

        verify(documentRepository).abortReindex(eq(OTHER_TENANT_ID), eq(DOCUMENT_ID), eq("v2"), any(), any());
    }

    @Test
    void deletionSweepRepublishesVectorRemovalsWithoutTouchingMysqlRows() {
        when(documentRepository.findUnconfirmedDeletions(any(Instant.class), eq(50)))
                .thenReturn(List.of(tenantDocument(deleted())));

        job.republishUnconfirmedDeletions();

        ArgumentCaptor<VectorChangeRequestedEvent> event =
                ArgumentCaptor.forClass(VectorChangeRequestedEvent.class);
        verify(documentEventPublisher).publishVectorChangeRequested(event.capture());
        assertThat(event.getValue().operation()).isEqualTo("DELETE_DOCUMENT");
        assertThat(event.getValue().documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(event.getValue().userId()).isEqualTo(USER_ID);
        assertThat(event.getValue().occurredAt()).isEqualTo(NOW);
        verify(documentRepository, never()).deleteByIdAndUser(eq(TENANT_ID), any(), any());
        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
    }

    @Test
    void deletionSweepRepublishesForEveryTenant() {
        when(documentRepository.findUnconfirmedDeletions(any(Instant.class), eq(50)))
                .thenReturn(List.of(
                        tenantDocument(TENANT_ID, deleted()),
                        tenantDocument(OTHER_TENANT_ID, deleted())));

        job.republishUnconfirmedDeletions();

        verify(documentEventPublisher, times(2)).publishVectorChangeRequested(any());
    }

    @Test
    void aFailingDeletionRepublishIsLoggedAndLeftForTheNextSweep() {
        when(documentRepository.findUnconfirmedDeletions(any(Instant.class), eq(50)))
                .thenReturn(List.of(tenantDocument(deleted()), tenantDocument(deleted())));
        doThrow(new IllegalStateException("mq down"))
                .when(documentEventPublisher)
                .publishVectorChangeRequested(any());

        job.republishUnconfirmedDeletions();

        verify(documentEventPublisher, times(2)).publishVectorChangeRequested(any());
    }

    private static TenantDocument tenantDocument(DocumentRow document) {
        return tenantDocument(TENANT_ID, document);
    }

    private static TenantDocument tenantDocument(UUID tenantId, DocumentRow document) {
        return new TenantDocument(tenantId, document);
    }

    private static DocumentRow document(int retryCount) {
        return row(DocumentStatus.PARSING, retryCount, "v1", null, null);
    }

    private static DocumentRow rebuilding() {
        return row(DocumentStatus.INDEXED, 0, "v1", "v2", null);
    }

    private static DocumentRow deleted() {
        return row(DocumentStatus.INDEXED, 0, "v1", null, NOW.minus(Duration.ofMinutes(30)));
    }

    private static DocumentRow row(DocumentStatus status, int retryCount, String indexVersion,
            String pendingIndexVersion, Instant deletedAt) {
        return new DocumentRow(
                DOCUMENT_ID,
                USER_ID,
                "notes.pdf",
                null,
                "application/pdf",
                1024,
                "d41d8cd98f00b204e9800998ecf8427e",
                "documents/" + USER_ID + "/" + DOCUMENT_ID + "/source",
                status,
                retryCount,
                3,
                null,
                null,
                indexVersion,
                pendingIndexVersion,
                null,
                null,
                NOW.minus(Duration.ofMinutes(30)),
                null,
                deletedAt,
                NOW,
                NOW);
    }
}
