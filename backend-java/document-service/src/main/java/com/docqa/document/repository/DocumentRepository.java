package com.docqa.document.repository;

import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    Optional<DocumentRow> findById(UUID tenantId, UUID id);

    Optional<DocumentRow> findByIdAndUser(UUID tenantId, UUID id, UUID userId);

    Optional<DocumentRow> findByUserAndMd5(UUID tenantId, UUID userId, String md5);

    void insert(UUID tenantId, DocumentRow document);

    int deleteByIdAndUser(UUID tenantId, UUID id, UUID userId);

    int markParsing(UUID tenantId, UUID id, UUID userId, Instant now);

    int markIndexed(UUID tenantId, UUID id, String indexVersion, Long vectorRows, Instant now);

    int promotePendingIndexVersion(UUID tenantId, UUID id, String pendingIndexVersion, Long vectorRows, Instant now);

    int recordVectorConfirmation(UUID tenantId, UUID id, long remainingRows, Instant confirmedAt);

    int markFailed(UUID tenantId, UUID id, String errorCode, String errorMessage);

    int markDead(UUID tenantId, UUID id, String errorCode, String errorMessage);

    int incrementRetryAndMarkParsing(UUID tenantId, UUID id, Instant now);

    int updateTitle(UUID tenantId, UUID id, UUID userId, String title);

    int softDelete(UUID tenantId, UUID id, UUID userId, Instant now);

    int resetRetryAndMarkParsing(UUID tenantId, UUID id, UUID userId, Instant now);

    int beginReindex(UUID tenantId, UUID id, UUID userId, String pendingIndexVersion, Instant now);

    int abortReindex(UUID tenantId, UUID id, String pendingIndexVersion, String errorCode, String errorMessage);

    List<DocumentRow> findStaleParsing(UUID tenantId, Instant threshold, int limit);

    List<DocumentRow> findStaleReindex(UUID tenantId, Instant threshold, int limit);

    List<DocumentRow> findUnconfirmedDeletions(UUID tenantId, Instant deletedBefore, int limit);

    List<DocumentRow> findUserPage(UUID tenantId, UUID userId, DocumentStatus status, String keyword, String normalizedTagName,
            Instant beforeCreatedAt, UUID beforeId, int limit);
}
