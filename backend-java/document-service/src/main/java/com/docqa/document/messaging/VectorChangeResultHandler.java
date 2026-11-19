package com.docqa.document.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.TenantDocument;
import com.docqa.document.repository.DocumentRepository;
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

    public VectorChangeResultHandler(
            DocumentRepository documentRepository,
            MqIdempotencyMarker idempotencyMarker,
            ObjectMapper objectMapper,
            Clock clock) {
        this.documentRepository = documentRepository;
        this.idempotencyMarker = idempotencyMarker;
        this.objectMapper = objectMapper;
        this.clock = clock;
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
            LOGGER.warn(
                    "Vector change failed; business state kept and consistency stays readable:"
                            + " documentId={}, operation={}, indexVersion={}, errorCode={}",
                    document.id(),
                    operation,
                    event.indexVersion(),
                    event.errorCode());
            return;
        }
        if (operation == VectorChangeOperation.DELETE_VERSION) {
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
