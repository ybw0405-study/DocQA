package com.docqa.document.messaging;

import com.docqa.common.trace.TraceIdContext;
import com.docqa.document.config.DocumentProperties;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.domain.TenantDocument;
import com.docqa.document.repository.DocumentRepository;
import com.docqa.document.repository.VectorCleanupTaskRepository;
import java.time.Clock;
import java.time.Duration;
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

    private final DocumentRepository documentRepository;
    private final DocumentEventPublisher documentEventPublisher;
    private final DocumentProperties properties;
    private final Clock clock;
    private final VectorCleanupTaskRepository cleanupTasks;

    public DocumentReconciliationJob(
            DocumentRepository documentRepository,
            DocumentEventPublisher documentEventPublisher,
            DocumentProperties properties,
            Clock clock,
            VectorCleanupTaskRepository cleanupTasks) {
        this.documentRepository = documentRepository;
        this.documentEventPublisher = documentEventPublisher;
        this.properties = properties;
        this.clock = clock;
        this.cleanupTasks = cleanupTasks;
    }

    @Scheduled(
            fixedDelayString = "${docqa.document.reconcile-interval-ms:300000}",
            initialDelayString = "${docqa.document.reconcile-initial-delay-ms:60000}")
    public void reconcileStaleParsing() {
        Instant now = clock.instant();
        Instant threshold = now.minus(properties.getStaleThreshold());
        List<TenantDocument> documents = documentRepository.findStaleParsing(
                threshold,
                properties.getReconcileBatchSize());
        for (TenantDocument entry : documents) {
            reconcile(entry.tenantId(), entry.document(), now);
        }
    }

    @Scheduled(
            fixedDelayString = "${docqa.document.reconcile-interval-ms:300000}",
            initialDelayString = "${docqa.document.reconcile-initial-delay-ms:90000}")
    public void abortStaleReindex() {
        Instant now = clock.instant();
        Instant threshold = now.minus(properties.getStaleThreshold());
        List<TenantDocument> documents = documentRepository.findStaleReindex(
                threshold,
                properties.getReconcileBatchSize());
        for (TenantDocument entry : documents) {
            DocumentRow document = entry.document();
            int updated = documentRepository.abortReindex(
                    entry.tenantId(),
                    document.id(),
                    document.pendingIndexVersion(),
                    "COM-INTERNAL-001",
                    REINDEX_TIMEOUT_MESSAGE);
            LOGGER.warn(
                    "Rebuild timed out, old index version kept: tenantId={}, documentId={}, pendingVersion={}, aborted={}",
                    entry.tenantId(),
                    document.id(),
                    document.pendingIndexVersion(),
                    updated);
            // Python 可能已把 pending 版本的向量写进 PG，而 parse.completed 丢失。
            // 只回滚 MySQL 会把这批向量永久留在数据面；回滚成功时补发一次带租户信封的版本删除。
            if (updated > 0 && document.pendingIndexVersion() != null
                    && !document.pendingIndexVersion().isBlank()) {
                // 先落 outbox 再发消息；发布失败时任务仍在表里，下轮重投。
                cleanupTasks.enqueue(
                        entry.tenantId(),
                        document.id(),
                        document.userId(),
                        document.pendingIndexVersion(),
                        VectorChangeOperation.DELETE_VERSION.name(),
                        now);
                publishVectorChange(VectorChangeRequestedEvent.deleteVersion(
                        TraceIdContext.currentOrCreate(),
                        document.id(),
                        document.userId(),
                        entry.tenantId(),
                        document.pendingIndexVersion(),
                        now));
            }
        }
    }

    @Scheduled(
            fixedDelayString = "${docqa.document.reconcile-interval-ms:300000}",
            initialDelayString = "${docqa.document.reconcile-initial-delay-ms:120000}")
    public void republishUnconfirmedDeletions() {
        Instant now = clock.instant();
        Instant deletedBefore = now.minus(properties.getStaleThreshold());
        List<TenantDocument> documents = documentRepository.findUnconfirmedDeletions(
                deletedBefore,
                properties.getReconcileBatchSize());
        for (TenantDocument entry : documents) {
            DocumentRow document = entry.document();
            publishVectorChange(VectorChangeRequestedEvent.deleteDocument(
                    TraceIdContext.currentOrCreate(),
                    document.id(),
                    document.userId(),
                    entry.tenantId(),
                    now));
        }
    }

    /**
     * 重投「已发布但尚未收到成功确认」的旧版本删除。
     *
     * <p>覆盖三种以前会永久残留孤儿向量的情形：发布时 MQ 抛错、消费端回 FAILED、
     * 以及消息本身丢失/进程重启。只有 {@link VectorChangeResultHandler} 收到
     * completed=true 才会从 outbox 删除该行；FAILED 不删、不 ACK，继续有限次重投。
     */
    @Scheduled(
            fixedDelayString = "${docqa.document.reconcile-interval-ms:300000}",
            initialDelayString = "${docqa.document.reconcile-initial-delay-ms:150000}")
    public void republishPendingVectorCleanups() {
        Instant now = clock.instant();
        // 给首次发布留 30s 窗口，避免刚入队就被重复投递；updated_at 升序保证老任务优先。
        Instant eligibleBefore = now.minus(Duration.ofSeconds(30));
        List<VectorCleanupTaskRepository.Task> tasks =
                cleanupTasks.findPending(eligibleBefore, properties.getReconcileBatchSize());
        for (VectorCleanupTaskRepository.Task task : tasks) {
            if (!VectorChangeOperation.DELETE_VERSION.name().equals(task.operation())) {
                LOGGER.warn(
                        "Unsupported vector cleanup operation in outbox; kept for operator review:"
                                + " id={}, operation={}",
                        task.id(),
                        task.operation());
                continue;
            }
            try {
                documentEventPublisher.publishVectorChangeRequested(
                        VectorChangeRequestedEvent.deleteVersion(
                                TraceIdContext.currentOrCreate(),
                                task.documentId(),
                                task.userId(),
                                task.tenantId(),
                                task.indexVersion(),
                                now));
            } catch (RuntimeException ex) {
                LOGGER.error(
                        "Unable to republish vector cleanup task; row stays in outbox:"
                                + " id={}, documentId={}, indexVersion={}",
                        task.id(),
                        task.documentId(),
                        task.indexVersion(),
                        ex);
            } finally {
                // 无论发布成功与否都记一次尝试，既避免下一轮立刻重复投递，
                // 也让 attempts 达到上限后停止重投（行仍保留供运维排查）。
                cleanupTasks.markAttempted(task.id(), now);
            }
        }
    }

    private void reconcile(UUID tenantId, DocumentRow document, Instant now) {
        if (document.retryCount() >= document.maxRetry()) {
            documentRepository.markDead(tenantId, document.id(), "COM-INTERNAL-001", "解析超时，已达到最大重试次数。");
            return;
        }
        int nextRetry = document.retryCount() + 1;
        ParseRequestedEvent event = new ParseRequestedEvent(
                UUID.randomUUID(),
                TraceIdContext.currentOrCreate(),
                document.id(),
                document.userId(),
                tenantId,
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

        int updated = documentRepository.incrementRetryAndMarkParsing(tenantId, document.id(), now);
        if (updated == 0) {
            DocumentRow current = documentRepository.findById(tenantId, document.id()).orElse(null);
            if (current != null
                    && current.retryCount() >= current.maxRetry()
                    && (current.status() == DocumentStatus.PARSING
                            || current.status() == DocumentStatus.FAILED)) {
                documentRepository.markDead(tenantId, current.id(), "COM-INTERNAL-001", "解析超时，已达到最大重试次数。");
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
