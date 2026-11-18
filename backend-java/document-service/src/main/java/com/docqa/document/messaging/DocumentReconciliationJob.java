package com.docqa.document.messaging;

import com.docqa.common.tenant.TenantContext;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.document.config.DocumentProperties;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.repository.DocumentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DocumentReconciliationJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentReconciliationJob.class);
    private static final String REINDEX_TIMEOUT_MESSAGE = "重建索引超时，已保留原索引版本，可稍后再次发起重建。";
    // P0: 存量事件归属默认租户，P1 随事件携带 tenant_id
    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;

    private final DocumentRepository documentRepository;
    private final DocumentEventPublisher documentEventPublisher;
    private final DocumentProperties properties;
    private final Clock clock;

    public DocumentReconciliationJob(
            DocumentRepository documentRepository,
            DocumentEventPublisher documentEventPublisher,
            DocumentProperties properties,
            Clock clock) {
        this.documentRepository = documentRepository;
        this.documentEventPublisher = documentEventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${docqa.document.reconcile-interval-ms:300000}",
            initialDelayString = "${docqa.document.reconcile-initial-delay-ms:60000}")
    public void reconcileStaleParsing() {
        Instant now = clock.instant();
        Instant threshold = now.minus(properties.getStaleThreshold());
        List<DocumentRow> documents = documentRepository.findStaleParsing(
                TENANT_ID,
                threshold,
                properties.getReconcileBatchSize());
        for (DocumentRow document : documents) {
            reconcile(document, now);
        }
    }

    @Scheduled(
            fixedDelayString = "${docqa.document.reconcile-interval-ms:300000}",
            initialDelayString = "${docqa.document.reconcile-initial-delay-ms:90000}")
    public void abortStaleReindex() {
        Instant now = clock.instant();
        Instant threshold = now.minus(properties.getStaleThreshold());
        List<DocumentRow> documents = documentRepository.findStaleReindex(
                TENANT_ID,
                threshold,
                properties.getReconcileBatchSize());
        for (DocumentRow document : documents) {
            int updated = documentRepository.abortReindex(
                    TENANT_ID,
                    document.id(),
                    document.pendingIndexVersion(),
                    "COM-INTERNAL-001",
                    REINDEX_TIMEOUT_MESSAGE);
            LOGGER.warn(
                    "Rebuild timed out, old index version kept: documentId={}, pendingVersion={}, aborted={}",
                    document.id(),
                    document.pendingIndexVersion(),
                    updated);
        }
    }

    @Scheduled(
            fixedDelayString = "${docqa.document.reconcile-interval-ms:300000}",
            initialDelayString = "${docqa.document.reconcile-initial-delay-ms:120000}")
    public void republishUnconfirmedDeletions() {
        Instant now = clock.instant();
        Instant deletedBefore = now.minus(properties.getStaleThreshold());
        List<DocumentRow> documents = documentRepository.findUnconfirmedDeletions(
                TENANT_ID,
                deletedBefore,
                properties.getReconcileBatchSize());
        for (DocumentRow document : documents) {
            publishVectorChange(VectorChangeRequestedEvent.deleteDocument(
                    TraceIdContext.currentOrCreate(),
                    document.id(),
                    document.userId(),
                    now));
        }
    }

    private void reconcile(DocumentRow document, Instant now) {
        if (document.retryCount() >= document.maxRetry()) {
            documentRepository.markDead(TENANT_ID, document.id(), "COM-INTERNAL-001", "解析超时，已达到最大重试次数。");
            return;
        }
        int nextRetry = document.retryCount() + 1;
        ParseRequestedEvent event = new ParseRequestedEvent(
                UUID.randomUUID(),
                TraceIdContext.currentOrCreate(),
                document.id(),
                document.userId(),
                document.indexVersion(),
                document.objectKey(),
                document.fileName(),
                document.contentType(),
                document.sizeBytes(),
                document.md5(),
                nextRetry,
                now);
        try {
            documentEventPublisher.publishParseRequested(event);
        } catch (RuntimeException ex) {
            LOGGER.error("Unable to republish stale parse.requested: documentId={}", document.id(), ex);
            return;
        }

        int updated = documentRepository.incrementRetryAndMarkParsing(TENANT_ID, document.id(), now);
        if (updated == 0) {
            DocumentRow current = documentRepository.findById(TENANT_ID, document.id()).orElse(null);
            if (current != null
                    && current.retryCount() >= current.maxRetry()
                    && (current.status() == DocumentStatus.PARSING
                            || current.status() == DocumentStatus.FAILED)) {
                documentRepository.markDead(TENANT_ID, current.id(), "COM-INTERNAL-001", "解析超时，已达到最大重试次数。");
            }
            return;
        }
        LOGGER.info(
                "Reconciled stale parsing document: documentId={}, retryCount={}",
                document.id(),
                nextRetry);
    }

    private void publishVectorChange(VectorChangeRequestedEvent event) {
        try {
            documentEventPublisher.publishVectorChangeRequested(event);
            LOGGER.info(
                    "Republished vector deletion: documentId={}, traceId={}",
                    event.documentId(),
                    event.traceId());
        } catch (RuntimeException ex) {
            LOGGER.error("Unable to republish vector.change.requested: documentId={}", event.documentId(), ex);
        }
    }
}
