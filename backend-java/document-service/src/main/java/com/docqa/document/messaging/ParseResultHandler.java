package com.docqa.document.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.metrics.BusinessMetrics;
import com.docqa.common.tenant.TenantContext;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.document.domain.ChunkEntry;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.repository.ChunkRepository;
import com.docqa.document.repository.DocumentRepository;
import com.docqa.document.support.SafeText;
import com.docqa.document.support.TraceScope;
import com.docqa.document.transaction.TransactionRunner;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ParseResultHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseResultHandler.class);
    private static final String ERROR_CODE_PATTERN = "^[A-Z]{2,4}-[A-Z_]+-[0-9]{3}$";
    private static final String REBUILD_FAILED_MESSAGE = "重建索引失败，已保留原索引版本，可稍后再次发起重建。";
    // P0: 存量事件归属默认租户，P1 随事件携带 tenant_id
    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentEventPublisher documentEventPublisher;
    private final TransactionRunner transactionRunner;
    private final MqIdempotencyMarker idempotencyMarker;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final BusinessMetrics metrics;

    public ParseResultHandler(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            DocumentEventPublisher documentEventPublisher,
            TransactionRunner transactionRunner,
            MqIdempotencyMarker idempotencyMarker,
            ObjectMapper objectMapper,
            Clock clock,
            BusinessMetrics metrics) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.documentEventPublisher = documentEventPublisher;
        this.transactionRunner = transactionRunner;
        this.idempotencyMarker = idempotencyMarker;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.metrics = metrics;
    }

    public void handleCompleted(String messageId, byte[] body) {
        if (idempotencyMarker.alreadyProcessed("parse.completed", messageId)) {
            return;
        }
        ParseCompletedEvent event;
        try {
            event = objectMapper.readValue(body, ParseCompletedEvent.class);
        } catch (IOException ex) {
            LOGGER.error("Invalid parse.completed payload: messageId={}", messageId, ex);
            idempotencyMarker.markProcessed("parse.completed", messageId);
            return;
        }
        TraceScope.run(event.traceId(), () -> handleCompletedEvent(messageId, event));
    }

    public void handleFailed(String messageId, byte[] body) {
        if (idempotencyMarker.alreadyProcessed("parse.failed", messageId)) {
            return;
        }
        ParseFailedEvent event;
        try {
            event = objectMapper.readValue(body, ParseFailedEvent.class);
        } catch (IOException ex) {
            LOGGER.error("Invalid parse.failed payload: messageId={}", messageId, ex);
            idempotencyMarker.markProcessed("parse.failed", messageId);
            return;
        }
        TraceScope.run(event.traceId(), () -> handleFailedEvent(messageId, event));
    }

    private void handleCompletedEvent(String messageId, ParseCompletedEvent event) {
        if (event.documentId() == null || event.indexVersion() == null) {
            LOGGER.error("parse.completed is missing documentId or indexVersion: messageId={}", messageId);
            idempotencyMarker.markProcessed("parse.completed", messageId);
            return;
        }
        DocumentRow document = documentRepository.findById(TENANT_ID, event.documentId()).orElse(null);
        if (document == null) {
            LOGGER.warn("parse.completed for unknown document: documentId={}", event.documentId());
            idempotencyMarker.markProcessed("parse.completed", messageId);
            return;
        }
        if (document.deleted()) {
            LOGGER.warn(
                    "Dropping parse.completed for a deleted document: documentId={}, eventVersion={}",
                    document.id(),
                    event.indexVersion());
            idempotencyMarker.markProcessed("parse.completed", messageId);
            return;
        }
        boolean promotesRebuild = event.indexVersion().equals(document.pendingIndexVersion());
        if (!promotesRebuild && !isPendingNormalParse(document, event.indexVersion())) {
            LOGGER.warn(
                    "Ignoring stale parse.completed: documentId={}, status={}, version={}, pendingVersion={},"
                            + " eventVersion={}",
                    document.id(),
                    document.status(),
                    document.indexVersion(),
                    document.pendingIndexVersion(),
                    event.indexVersion());
            idempotencyMarker.markProcessed("parse.completed", messageId);
            return;
        }

        List<ChunkEntry> chunks;
        try {
            chunks = toChunks(document.id(), event.indexVersion(), event.chunks());
        } catch (IllegalArgumentException ex) {
            LOGGER.error("Invalid parse.completed chunks: documentId={}", document.id(), ex);
            finishWithoutIndex(document, event.indexVersion(), "DOC-VALIDATION-004", "解析结果不完整，请重新上传。");
            idempotencyMarker.markProcessed("parse.completed", messageId);
            return;
        }

        Instant now = clock.instant();
        IndexedOutcome outcome = transactionRunner.execute(
                () -> applyIndexed(document.id(), event.indexVersion(), event.vectorRows(), chunks, now));
        idempotencyMarker.markProcessed("parse.completed", messageId);
        if (!outcome.applied()) {
            LOGGER.warn(
                    "parse.completed was not applied due to a concurrent state change: documentId={}",
                    document.id());
            return;
        }
        LOGGER.info("Document indexed: documentId={}, chunks={}", document.id(), chunks.size());
        metrics.documentParse(parseDuration(document, now), "INDEXED");
        if (outcome.obsoleteIndexVersion() != null) {
            publishVectorChangeQuietly(VectorChangeRequestedEvent.deleteVersion(
                    TraceIdContext.currentOrCreate(),
                    document.id(),
                    document.userId(),
                    outcome.obsoleteIndexVersion(),
                    now));
        }
    }

    /**
     * 解析耗时 = {@code parsing_started_at} → 落库时刻。
     *
     * <p>{@code parsing_started_at} 由 {@code markParsing} 在发出 parse-requested 之前写入；
     * 若为空（历史数据或对账重建路径），退回 {@code updated_at}——宁可粗一点也不要丢样本，
     * 因为这条序列的用途是发现「解析变慢」，缺样本比不精确更伤。
     */
    private Duration parseDuration(DocumentRow document, Instant finishedAt) {
        Instant startedAt = document.parsingStartedAt() == null ? document.updatedAt() : document.parsingStartedAt();
        if (startedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, finishedAt);
    }

    private IndexedOutcome applyIndexed(
            UUID documentId,
            String eventVersion,
            Long vectorRows,
            List<ChunkEntry> chunks,
            Instant now) {
        DocumentRow current = documentRepository.findById(TENANT_ID, documentId).orElse(null);
        if (current == null || current.deleted()) {
            return new IndexedOutcome(false, null);
        }
        if (eventVersion.equals(current.pendingIndexVersion())) {
            chunkRepository.upsertAll(TENANT_ID, chunks);
            if (documentRepository.promotePendingIndexVersion(TENANT_ID, documentId, eventVersion, vectorRows, now) == 0) {
                throw new IllegalStateException("Unable to promote pending index version");
            }
            String obsolete = current.indexVersion();
            chunkRepository.deleteByVersion(TENANT_ID, documentId, obsolete);
            return new IndexedOutcome(true, obsolete);
        }
        if (!isPendingNormalParse(current, eventVersion)) {
            return new IndexedOutcome(false, null);
        }
        chunkRepository.upsertAll(TENANT_ID, chunks);
        if (documentRepository.markIndexed(TENANT_ID, documentId, eventVersion, vectorRows, now) == 0) {
            throw new IllegalStateException("Unable to mark document INDEXED");
        }
        return new IndexedOutcome(true, null);
    }

    private void handleFailedEvent(String messageId, ParseFailedEvent event) {
        if (event.documentId() == null || event.indexVersion() == null) {
            LOGGER.error("parse.failed is missing documentId or indexVersion: messageId={}", messageId);
            idempotencyMarker.markProcessed("parse.failed", messageId);
            return;
        }
        DocumentRow document = documentRepository.findById(TENANT_ID, event.documentId()).orElse(null);
        if (document == null) {
            LOGGER.warn("parse.failed for unknown document: documentId={}", event.documentId());
            idempotencyMarker.markProcessed("parse.failed", messageId);
            return;
        }
        if (document.deleted()) {
            LOGGER.warn("Dropping parse.failed for a deleted document: documentId={}", document.id());
            idempotencyMarker.markProcessed("parse.failed", messageId);
            return;
        }
        String errorCode = validErrorCode(event.errorCode()) ? event.errorCode() : "COM-INTERNAL-001";
        String errorMessage = SafeText.errorMessage(event.errorMessage(), "解析失败，请稍后重试。");

        if (event.indexVersion().equals(document.pendingIndexVersion())) {
            documentRepository.abortReindex(TENANT_ID, document.id(), event.indexVersion(), errorCode, REBUILD_FAILED_MESSAGE);
            LOGGER.warn(
                    "Rebuild aborted, old index version kept: documentId={}, failedVersion={}, errorCode={}",
                    document.id(),
                    event.indexVersion(),
                    errorCode);
            // 重建失败不可重试（旧索引仍可用），因此 retryable 恒为 false。
            metrics.documentParseFailure(errorCode, false);
            metrics.documentParse(parseDuration(document, clock.instant()), "FAILED");
            idempotencyMarker.markProcessed("parse.failed", messageId);
            return;
        }
        if (document.status() == DocumentStatus.INDEXED
                || document.status() == DocumentStatus.DEAD
                || !document.indexVersion().equals(event.indexVersion())) {
            idempotencyMarker.markProcessed("parse.failed", messageId);
            return;
        }

        Instant now = clock.instant();
        metrics.documentParseFailure(errorCode, event.retryable());
        metrics.documentParse(parseDuration(document, now), "FAILED");

        if (!event.retryable()) {
            markFailed(document.id(), errorCode, errorMessage);
            idempotencyMarker.markProcessed("parse.failed", messageId);
            return;
        }

        Integer retryCount = transactionRunner.execute(() -> {
            int updated = documentRepository.incrementRetryAndMarkParsing(TENANT_ID, document.id(), now);
            if (updated == 0) {
                DocumentRow current = documentRepository.findById(TENANT_ID, document.id()).orElse(null);
                if (current != null
                        && (current.status() == DocumentStatus.PARSING
                                || current.status() == DocumentStatus.FAILED)
                        && current.retryCount() >= current.maxRetry()) {
                    documentRepository.markDead(TENANT_ID, current.id(), errorCode, "解析重试次数已耗尽。");
                }
                return -1;
            }
            return documentRepository.findById(TENANT_ID, document.id())
                    .map(DocumentRow::retryCount)
                    .orElse(-1);
        });

        if (retryCount != null && retryCount >= 0) {
            DocumentRow current = documentRepository.findById(TENANT_ID, document.id()).orElse(null);
            if (current != null) {
                ParseRequestedEvent requested = new ParseRequestedEvent(
                        UUID.randomUUID(),
                        TraceIdContext.currentOrCreate(),
                        current.id(),
                        current.userId(),
                        current.indexVersion(),
                        current.objectKey(),
                        current.fileName(),
                        current.contentType(),
                        current.sizeBytes(),
                        current.md5(),
                        retryCount,
                        now);
                try {
                    documentEventPublisher.publishParseRequested(requested, delayLevel(retryCount));
                } catch (RuntimeException ex) {
                    LOGGER.error(
                            "Unable to republish parse.requested: documentId={}, retryCount={}",
                            current.id(),
                            retryCount,
                            ex);
                }
            }
        }
        idempotencyMarker.markProcessed("parse.failed", messageId);
    }

    private void finishWithoutIndex(
            DocumentRow document,
            String eventVersion,
            String errorCode,
            String errorMessage) {
        if (eventVersion.equals(document.pendingIndexVersion())) {
            transactionRunner.execute(
                    () -> documentRepository.abortReindex(TENANT_ID, document.id(), eventVersion, errorCode, errorMessage));
            return;
        }
        markFailed(document.id(), errorCode, errorMessage);
    }

    private static boolean isPendingNormalParse(DocumentRow document, String eventVersion) {
        return document.status() == DocumentStatus.PARSING
                && document.pendingIndexVersion() == null
                && eventVersion.equals(document.indexVersion());
    }

    private List<ChunkEntry> toChunks(UUID documentId, String indexVersion, List<ParsedChunk> parsedChunks) {
        if (parsedChunks == null) {
            throw new IllegalArgumentException("chunks is missing");
        }
        Set<Integer> indexes = new HashSet<>();
        List<ChunkEntry> chunks = new ArrayList<>(parsedChunks.size());
        for (ParsedChunk parsed : parsedChunks) {
            if (parsed == null || parsed.chunkIndex() < 0 || !indexes.add(parsed.chunkIndex())) {
                throw new IllegalArgumentException("chunk indexes must be unique and non-negative");
            }
            if (parsed.vectorId() == null || parsed.vectorId().isBlank()) {
                throw new IllegalArgumentException("vectorId is missing");
            }
            UUID.fromString(parsed.vectorId());
            if (parsed.contentHash() == null || !parsed.contentHash().matches("^[a-f0-9]{64}$")) {
                throw new IllegalArgumentException("contentHash is invalid");
            }
            if (parsed.tokenCount() < 0) {
                throw new IllegalArgumentException("tokenCount is negative");
            }
            chunks.add(new ChunkEntry(
                    UUID.randomUUID(),
                    documentId,
                    parsed.chunkIndex(),
                    parsed.vectorId(),
                    parsed.contentHash(),
                    parsed.tokenCount(),
                    indexVersion));
        }
        return chunks;
    }

    private void markFailed(UUID documentId, String errorCode, String errorMessage) {
        transactionRunner.execute(() -> documentRepository.markFailed(TENANT_ID, documentId, errorCode, errorMessage));
    }

    private void publishVectorChangeQuietly(VectorChangeRequestedEvent event) {
        try {
            documentEventPublisher.publishVectorChangeRequested(event);
        } catch (RuntimeException ex) {
            LOGGER.error(
                    "vector.change.requested publish failed; orphan vectors stay unreachable until S9 sweep:"
                            + " traceId={}, documentId={}, indexVersion={}",
                    event.traceId(),
                    event.documentId(),
                    event.indexVersion(),
                    ex);
        }
    }

    private int delayLevel(int retryCount) {
        return Math.min(3 + retryCount, 8);
    }

    private boolean validErrorCode(String errorCode) {
        return errorCode != null && errorCode.matches(ERROR_CODE_PATTERN);
    }

    private record IndexedOutcome(boolean applied, String obsoleteIndexVersion) {
    }
}
