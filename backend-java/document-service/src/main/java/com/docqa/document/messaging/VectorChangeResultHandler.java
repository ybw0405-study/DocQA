package com.docqa.document.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.TenantDocument;
import com.docqa.document.repository.DocumentRepository;
import com.docqa.document.repository.VectorCleanupTaskRepository;
import com.docqa.document.support.TraceScope;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class VectorChangeResultHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(VectorChangeResultHandler.class);
    private static final String EVENT_NAME = "vector-change-completed";

    private final DocumentRepository documentRepository;
    private final MqIdempotencyMarker idempotencyMarker;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final VectorCleanupTaskRepository cleanupTasks;

    public VectorChangeResultHandler(
            DocumentRepository documentRepository,
            MqIdempotencyMarker idempotencyMarker,
            ObjectMapper objectMapper,
            Clock clock,
            VectorCleanupTaskRepository cleanupTasks) {
        this.documentRepository = documentRepository;
        this.idempotencyMarker = idempotencyMarker;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.cleanupTasks = cleanupTasks;
    }

    public void handle(String messageId, byte[] body) {
        if (idempotencyMarker.alreadyProcessed(EVENT_NAME, messageId)) {
            return;
        }
        VectorChangeCompletedEvent event;
        try {
            event = objectMapper.readValue(body, VectorChangeCompletedEvent.class);
        } catch (IOException ex) {
            LOGGER.error("Invalid vector-change-completed payload: messageId={}", messageId, ex);
            idempotencyMarker.markProcessed(EVENT_NAME, messageId);
            return;
        }
        TraceScope.run(event.traceId(), () -> handleEvent(event));
        idempotencyMarker.markProcessed(EVENT_NAME, messageId);
    }

    private void handleEvent(VectorChangeCompletedEvent event) {
        VectorChangeOperation operation = VectorChangeOperation.from(event.operation());
        if (event.documentId() == null || operation == null) {
            LOGGER.error(
                    "vector-change-completed is missing documentId or has an unknown operation:"
                            + " messageId={}, operation={}",
                    event.eventId(),
                    event.operation());
            return;
        }
        Optional<TenantDocument> found = documentRepository.findTenantDocumentById(event.documentId());
        if (found.isEmpty()) {
            LOGGER.warn("vector-change-completed for unknown document: documentId={}", event.documentId());
            return;
        }
        UUID tenantId = found.get().tenantId();
        DocumentRow document = found.get().document();
        if (!event.completed()) {
            // 修复前这里只打日志就 return，handle() 仍无条件 markProcessed，
            // 于是失败的删除确认被永久 ACK，孤儿向量再无任何重投机会。
            // 抛回 consumer 由 RECONSUME_LATER 有限次重投（最终进 DLQ 供运维介入），
            // 且不写幂等标记，避免「处理过」掩盖一次真正失败的删除。
            LOGGER.warn(
                    "Vector change failed; returning the confirmation to the broker:"
                            + " documentId={}, operation={}, indexVersion={}, errorCode={}",
                    document.id(),
                    operation,
                    event.indexVersion(),
                    event.errorCode());
            throw new IllegalStateException(
                    "vector change reported FAILED: documentId=" + document.id()
                            + ", operation=" + operation
                            + ", indexVersion=" + event.indexVersion()
                            + ", errorCode=" + event.errorCode());
        }
        if (operation == VectorChangeOperation.DELETE_VERSION) {
            // 只有真正删成功才出队。FAILED 走上面的异常分支，outbox 行保留，
            // 由 republishPendingVectorCleanups() 有限次重投，不会永久残留孤儿向量。
            if (event.indexVersion() != null && !event.indexVersion().isBlank()) {
                cleanupTasks.remove(
                        tenantId,
                        document.id(),
                        event.indexVersion(),
                        operation.name());
            }
            LOGGER.info(
                    "Obsolete index version removed from the vector side: documentId={}, indexVersion={},"
                            + " remainingRows={}",
                    document.id(),
                    event.indexVersion(),
                    event.remainingRows());
            return;
        }
        if (!document.deleted()) {
            LOGGER.warn(
                    "DELETE_DOCUMENT confirmation for a document without deleted_at: documentId={}",
                    document.id());
            return;
        }
        Long remainingRows = event.remainingRows();
        if (remainingRows == null) {
            LOGGER.warn(
                    "DELETE_DOCUMENT confirmation without remainingRows: documentId={}",
                    document.id());
            return;
        }
        Instant now = clock.instant();
        int updated = documentRepository.recordVectorConfirmation(tenantId, document.id(), remainingRows, now);
        LOGGER.info(
                "Vector rows confirmed after deletion: documentId={}, remainingRows={}, applied={}",
                document.id(),
                remainingRows,
                updated);
    }
}
