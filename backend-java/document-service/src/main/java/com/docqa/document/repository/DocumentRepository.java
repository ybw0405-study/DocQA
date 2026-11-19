package com.docqa.document.repository;

import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.domain.TenantDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    Optional<DocumentRow> findById(UUID tenantId, UUID id);

    /**
     * 系统级反查：按全局唯一的 documentId 读取行及其归属租户。
     *
     * <p>MQ 消费与定时任务没有 HTTP 租户上下文，用它取得事实租户；所有后续写操作必须带上
     * 返回的 tenantId，不能回落默认租户。
     */
    Optional<TenantDocument> findTenantDocumentById(UUID id);

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

    /** 系统级跨租户扫描：对账任务按全局时间阈值捞出所有租户的待处理行。 */
    List<TenantDocument> findStaleParsing(Instant threshold, int limit);

    List<TenantDocument> findStaleReindex(Instant threshold, int limit);

    List<TenantDocument> findUnconfirmedDeletions(Instant deletedBefore, int limit);

    List<DocumentRow> findUserPage(UUID tenantId, UUID userId, DocumentStatus status, String keyword, String normalizedTagName,
            Instant beforeCreatedAt, UUID beforeId, int limit);
}
