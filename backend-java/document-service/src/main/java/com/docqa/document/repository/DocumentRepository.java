package com.docqa.document.repository;

import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    Optional<DocumentRow> findById(UUID id);

    Optional<DocumentRow> findByIdAndUser(UUID id, UUID userId);

    Optional<DocumentRow> findByUserAndMd5(UUID userId, String md5);

    void insert(DocumentRow document);

    int deleteByIdAndUser(UUID id, UUID userId);

    int markParsing(UUID id, UUID userId, Instant now);

    int markIndexed(UUID id, String indexVersion, Long vectorRows, Instant now);

    int promotePendingIndexVersion(UUID id, String pendingIndexVersion, Long vectorRows, Instant now);

    int recordVectorConfirmation(UUID id, long remainingRows, Instant confirmedAt);

    int markFailed(UUID id, String errorCode, String errorMessage);

    int markDead(UUID id, String errorCode, String errorMessage);

    int incrementRetryAndMarkParsing(UUID id, Instant now);

    int updateTitle(UUID id, UUID userId, String title);

    int softDelete(UUID id, UUID userId, Instant now);

    int resetRetryAndMarkParsing(UUID id, UUID userId, Instant now);

    int beginReindex(UUID id, UUID userId, String pendingIndexVersion, Instant now);

    int abortReindex(UUID id, String pendingIndexVersion, String errorCode, String errorMessage);

    List<DocumentRow> findStaleParsing(Instant threshold, int limit);

    List<DocumentRow> findStaleReindex(Instant threshold, int limit);

    List<DocumentRow> findUnconfirmedDeletions(Instant deletedBefore, int limit);

    List<DocumentRow> findUserPage(UUID userId, DocumentStatus status, String keyword, String normalizedTagName,
            Instant beforeCreatedAt, UUID beforeId, int limit);
}
