package com.docqa.document.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.document.config.DocumentProperties;
import com.docqa.document.config.MinioProperties;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.domain.IdempotencyOperation;
import com.docqa.document.domain.TagName;
import com.docqa.document.domain.VectorConsistency;
import com.docqa.document.dto.CompleteUploadData;
import com.docqa.document.dto.CompleteUploadRequest;
import com.docqa.document.dto.CompletedPart;
import com.docqa.document.dto.DocumentListData;
import com.docqa.document.dto.DocumentListItemData;
import com.docqa.document.dto.DocumentRenameRequest;
import com.docqa.document.dto.DocumentStatusData;
import com.docqa.document.dto.DocumentTagsUpdateRequest;
import com.docqa.document.dto.PresignedPart;
import com.docqa.document.dto.UploadInitData;
import com.docqa.document.dto.UploadInitRequest;
import com.docqa.document.lock.DistributedLock;
import com.docqa.document.messaging.DocumentEventPublisher;
import com.docqa.document.messaging.ParseRequestedEvent;
import com.docqa.document.messaging.VectorChangeRequestedEvent;
import com.docqa.document.repository.ChunkRepository;
import com.docqa.document.repository.DocumentRepository;
import com.docqa.document.repository.DocumentTagRepository;
import com.docqa.document.storage.ObjectStorage;
import com.docqa.document.storage.ObjectStorageException;
import com.docqa.document.storage.UploadPart;
import com.docqa.document.support.Fingerprints;
import com.docqa.document.support.ObjectKeys;
import com.docqa.document.support.TagNormalizer;
import com.docqa.document.support.UploadPlan;
import com.docqa.document.transaction.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentService.class);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);
    private static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;
    private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9._:-]{8,64}$";
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_SEARCH_LENGTH = 120;
    private static final int MAX_TAGS_PER_DOCUMENT = 20;
    private static final Pattern INDEX_VERSION_PATTERN = Pattern.compile("^v(\\d{1,8})$");
    private static final String DELETED_MARKER = "DELETED";
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain");

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentTagRepository tagRepository;
    private final ObjectStorage objectStorage;
    private final UploadSessionStore uploadSessionStore;
    private final IdempotencyStore idempotencyStore;
    private final DocumentEventPublisher documentEventPublisher;
    private final DistributedLock distributedLock;
    private final TransactionRunner transactionRunner;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final DocumentProperties documentProperties;
    private final MinioProperties minioProperties;

    public DocumentService(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            DocumentTagRepository tagRepository,
            ObjectStorage objectStorage,
            UploadSessionStore uploadSessionStore,
            IdempotencyStore idempotencyStore,
            DocumentEventPublisher documentEventPublisher,
            DistributedLock distributedLock,
            TransactionRunner transactionRunner,
            Clock clock,
            ObjectMapper objectMapper,
            DocumentProperties documentProperties,
            MinioProperties minioProperties) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.tagRepository = tagRepository;
        this.objectStorage = objectStorage;
        this.uploadSessionStore = uploadSessionStore;
        this.idempotencyStore = idempotencyStore;
        this.documentEventPublisher = documentEventPublisher;
        this.distributedLock = distributedLock;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.documentProperties = documentProperties;
        this.minioProperties = minioProperties;
    }

    public UploadInitResult init(UUID userId, String idempotencyKey, UploadInitRequest request) {
        validateIdempotencyKey(idempotencyKey);
        validateInitRequest(request);
        String fingerprint = initFingerprint(request);
        String idempotencyLock = "idem:lock:" + userId + ":UPLOAD_INIT:" + idempotencyKey;

        return distributedLock.withLock(idempotencyLock, LOCK_WAIT, () -> {
            Optional<IdempotencyRecord> existing = idempotencyStore.find(
                    userId, IdempotencyOperation.UPLOAD_INIT, idempotencyKey);
            if (existing.isPresent()) {
                return replayInit(existing.get(), fingerprint);
            }

            String md5Lock = "upload:lock:" + userId + ":" + request.md5();
            UploadInitResult result = distributedLock.withLock(
                    md5Lock,
                    LOCK_WAIT,
                    () -> initByMd5(userId, request));
            saveIdempotency(userId, IdempotencyOperation.UPLOAD_INIT, idempotencyKey, fingerprint, result.data());
            return result;
        });
    }

    public CompleteUploadResult complete(
            UUID userId,
            UUID uploadId,
            String idempotencyKey,
            CompleteUploadRequest request) {
        validateIdempotencyKey(idempotencyKey);
        String fingerprint = completeFingerprint(request);
        String idempotencyLock = "idem:lock:" + userId + ":UPLOAD_COMPLETE:" + idempotencyKey;

        return distributedLock.withLock(idempotencyLock, LOCK_WAIT, () -> {
            Optional<IdempotencyRecord> existing = idempotencyStore.find(
                    userId, IdempotencyOperation.UPLOAD_COMPLETE, idempotencyKey);
            if (existing.isPresent()) {
                return replayComplete(existing.get(), fingerprint);
            }

            String uploadLock = "upload:complete:lock:" + uploadId;
            return distributedLock.withLock(
                    uploadLock,
                    LOCK_WAIT,
                    () -> completeLocked(userId, uploadId, idempotencyKey, fingerprint, request));
        });
    }

    public DocumentStatusData status(UUID userId, UUID documentId) {
        return view(userId, documentId);
    }

    public DocumentStatusData rename(
            UUID userId,
            UUID documentId,
            String idempotencyKey,
            DocumentRenameRequest request) {
        validateIdempotencyKey(idempotencyKey);
        String title = normalizeTitle(request.title());
        String fingerprint = Fingerprints.sha256("title\n" + title);
        return idempotent(userId, IdempotencyOperation.DOCUMENT_RENAME, idempotencyKey, fingerprint,
                DocumentStatusData.class, () -> {
                    DocumentRow document = requireOwnedDocument(documentId, userId);
                    if (transactionRunner.execute(
                            () -> documentRepository.updateTitle(TenantContext.require(), document.id(), userId, title)) == 0) {
                        throw lostDocument(documentId, userId);
                    }
                    return view(userId, document.id());
                }).data();
    }

    public DocumentStatusData replaceTags(
            UUID userId,
            UUID documentId,
            String idempotencyKey,
            DocumentTagsUpdateRequest request) {
        validateIdempotencyKey(idempotencyKey);
        List<TagName> tags = normalizeTags(request);
        String fingerprint = Fingerprints.sha256("tags\n" + tags.stream()
                .map(TagName::normalized)
                .sorted()
                .collect(Collectors.joining("\n")));
        return idempotent(userId, IdempotencyOperation.DOCUMENT_TAG_UPDATE, idempotencyKey, fingerprint,
                DocumentStatusData.class, () -> {
                    DocumentRow document = requireOwnedDocument(documentId, userId);
                    Instant now = clock.instant();
                    transactionRunner.execute(() -> {
                        Map<String, UUID> ids = tagRepository.resolveOrCreate(TenantContext.require(), userId, tags, now);
                        tagRepository.replaceLinks(TenantContext.require(), document.id(),
                                tags.stream().map(tag -> ids.get(tag.normalized())).toList(), now);
                        return Boolean.TRUE;
                    });
                    return view(userId, document.id());
                }).data();
    }

    public void delete(UUID userId, UUID documentId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        String fingerprint = Fingerprints.sha256("delete\n" + documentId);
        idempotent(userId, IdempotencyOperation.DOCUMENT_DELETE, idempotencyKey, fingerprint, String.class, () -> {
            DocumentRow document = requireOwnedDocument(documentId, userId);
            if (document.status() == DocumentStatus.PARSING) {
                throw new BusinessException(
                        ErrorCode.DOC_CONFLICT_001,
                        "文档正在解析中，请等待解析结束后再删除。");
            }
            Instant now = clock.instant();
            transactionRunner.execute(() -> {
                chunkRepository.deleteByDocument(TenantContext.require(), document.id());
                if (documentRepository.softDelete(TenantContext.require(), document.id(), userId, now) == 0) {
                    throw lostDocument(documentId, userId);
                }
                return Boolean.TRUE;
            });
            publishVectorChangeQuietly(VectorChangeRequestedEvent.deleteDocument(
                    TraceIdContext.currentOrCreate(), document.id(), userId,
                    TenantContext.require(), now));
            return DELETED_MARKER;
        });
    }

    public DocumentCommandResult retryParse(UUID userId, UUID documentId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        String fingerprint = Fingerprints.sha256("retry\n" + documentId);
        IdempotentOutcome<DocumentStatusData> outcome = idempotent(
                userId, IdempotencyOperation.DOCUMENT_RETRY, idempotencyKey, fingerprint,
                DocumentStatusData.class, () -> {
                    DocumentRow document = requireOwnedDocument(documentId, userId);
                    if (document.status() != DocumentStatus.FAILED && document.status() != DocumentStatus.DEAD) {
                        throw new BusinessException(
                                ErrorCode.DOC_CONFLICT_001,
                                "只有解析失败的文档可以重试，请先查询文档状态。");
                    }
                    Instant now = clock.instant();
                    if (documentRepository.resetRetryAndMarkParsing(TenantContext.require(), document.id(), userId, now) == 0) {
                        throw lostDocument(documentId, userId);
                    }
                    publishRequestedQuietly(document, document.indexVersion(), 0, now);
                    return view(userId, document.id());
                });
        return new DocumentCommandResult(outcome.data(), !outcome.replayed());
    }

    public DocumentCommandResult reindex(UUID userId, UUID documentId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        String fingerprint = Fingerprints.sha256("reindex\n" + documentId);
        IdempotentOutcome<DocumentStatusData> outcome = idempotent(
                userId, IdempotencyOperation.DOCUMENT_REINDEX, idempotencyKey, fingerprint,
                DocumentStatusData.class, () -> {
                    DocumentRow document = requireOwnedDocument(documentId, userId);
                    if (document.status() != DocumentStatus.INDEXED) {
                        throw new BusinessException(
                                ErrorCode.DOC_CONFLICT_001,
                                "只有已索引的文档可以重建索引，请先完成解析。");
                    }
                    if (document.rebuilding()) {
                        throw new BusinessException(
                                ErrorCode.DOC_CONFLICT_001,
                                "已有重建任务在途，请等待当前重建结束。");
                    }
                    String pendingVersion = nextIndexVersion(document.indexVersion());
                    Instant now = clock.instant();
                    if (documentRepository.beginReindex(TenantContext.require(), document.id(), userId, pendingVersion, now) == 0) {
                        throw lostDocument(documentId, userId);
                    }
                    publishRequestedQuietly(document, pendingVersion, 0, now);
                    return view(userId, document.id());
                });
        return new DocumentCommandResult(outcome.data(), !outcome.replayed());
    }

    public DocumentListData list(UUID userId, String statusParam, String queryParam, String tagParam,
            String cursorToken, int limit) {
        if (limit < MIN_PAGE_SIZE || limit > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "limit out of range",
                    List.of(FieldViolation.of("limit", "must be between 1 and 100")));
        }
        DocumentStatus status = parseStatusFilter(statusParam);
        String keyword = parseSearchQuery(queryParam);
        String tagName = parseTagFilter(tagParam);
        KeysetCursor cursor = parseCursor(cursorToken);
        List<DocumentRow> rows = documentRepository.findUserPage(
                TenantContext.require(),
                userId,
                status,
                keyword,
                tagName,
                cursor == null ? null : cursor.sortKey(),
                cursor == null ? null : cursor.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<DocumentRow> page = hasMore ? rows.subList(0, limit) : rows;
        List<UUID> pageIds = page.stream().map(DocumentRow::id).toList();
        Map<UUID, Long> chunkCounts = chunkRepository.countByDocuments(TenantContext.require(), pageIds);
        Map<UUID, List<String>> tagNames = tagRepository.findNamesByDocuments(TenantContext.require(), pageIds);
        List<DocumentListItemData> items = page.stream()
                .map(row -> new DocumentListItemData(
                        row.id(),
                        row.fileName(),
                        row.title(),
                        tagNames.getOrDefault(row.id(), List.of()),
                        row.contentType(),
                        row.sizeBytes(),
                        row.status().name(),
                        row.retryCount(),
                        row.maxRetry(),
                        row.errorCode(),
                        row.indexVersion(),
                        chunkCounts.getOrDefault(row.id(), 0L),
                        row.indexedAt(),
                        row.createdAt(),
                        row.updatedAt()))
                .toList();
        String nextCursor = hasMore
                ? KeysetCursor.of(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()).encode()
                : null;
        return new DocumentListData(items, nextCursor, limit);
    }

    private DocumentStatusData view(UUID userId, UUID documentId) {
        DocumentRow document = requireOwnedDocument(documentId, userId);
        long chunkCount = chunkRepository.count(TenantContext.require(), document.id(), document.indexVersion());
        return new DocumentStatusData(
                document.id(),
                document.fileName(),
                document.title(),
                tagRepository.findNames(TenantContext.require(), document.id()),
                document.status().name(),
                document.retryCount(),
                document.maxRetry(),
                document.errorCode(),
                document.errorMessage(),
                document.indexVersion(),
                document.pendingIndexVersion(),
                chunkCount,
                document.vectorRows(),
                document.vectorConfirmedAt(),
                VectorConsistency.of(chunkCount, document.vectorRows()).name(),
                document.indexedAt(),
                document.createdAt(),
                document.updatedAt());
    }

    private DocumentRow requireOwnedDocument(UUID documentId, UUID userId) {
        return documentRepository.findByIdAndUser(TenantContext.require(), documentId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_NOT_FOUND_001));
    }

    private BusinessException lostDocument(UUID documentId, UUID userId) {
        DocumentRow current = documentRepository.findByIdAndUser(TenantContext.require(), documentId, userId).orElse(null);
        if (current == null) {
            return new BusinessException(ErrorCode.DOC_NOT_FOUND_001);
        }
        return new BusinessException(ErrorCode.DOC_CONFLICT_001, "文档状态已变更，请查询状态后重试。");
    }

    private String normalizeTitle(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.strip();
        if (title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, null,
                    List.of(FieldViolation.of("title", "must be 1 to 120 characters after trimming")));
        }
        return title;
    }

    private List<TagName> normalizeTags(DocumentTagsUpdateRequest request) {
        List<String> rawTags = request == null ? null : request.tags();
        if (rawTags == null) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, null,
                    List.of(FieldViolation.of("tags", "must be an array, empty to clear")));
        }
        if (rawTags.size() > MAX_TAGS_PER_DOCUMENT) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, null,
                    List.of(FieldViolation.of("tags", "must contain at most 20 tags")));
        }
        List<TagName> tags = new ArrayList<>(rawTags.size());
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < rawTags.size(); index++) {
            TagName tag = TagNormalizer.of(rawTags.get(index));
            if (tag.normalized().isEmpty() || tag.normalized().length() > TagNormalizer.MAX_TAG_LENGTH) {
                throw new BusinessException(ErrorCode.COM_VALIDATION_001, null,
                        List.of(FieldViolation.of("tags[" + index + "]",
                                "must be 1 to 24 characters after normalization")));
            }
            if (!seen.add(tag.normalized())) {
                throw new BusinessException(ErrorCode.COM_VALIDATION_001, null,
                        List.of(FieldViolation.of("tags[" + index + "]",
                                "duplicates another tag after normalization")));
            }
            tags.add(tag);
        }
        return List.copyOf(tags);
    }

    private String nextIndexVersion(String currentVersion) {
        Matcher matcher = INDEX_VERSION_PATTERN.matcher(currentVersion == null ? "" : currentVersion);
        if (!matcher.matches()) {
            throw new BusinessException(
                    ErrorCode.DOC_CONFLICT_001,
                    "当前索引版本命名不符合 v{n} 规则，无法自动推导新版本。");
        }
        return "v" + (Long.parseLong(matcher.group(1)) + 1);
    }

    private static String parseSearchQuery(String value) {
        if (value == null) {
            return null;
        }
        String keyword = value.strip();
        if (keyword.isEmpty()) {
            return null;
        }
        if (keyword.length() > MAX_SEARCH_LENGTH) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, null,
                    List.of(FieldViolation.of("q", "must be at most 120 characters")));
        }
        return keyword;
    }

    private static String parseTagFilter(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        String normalized = TagNormalizer.of(stripped).normalized();
        if (normalized.length() > TagNormalizer.MAX_TAG_LENGTH) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, null,
                    List.of(FieldViolation.of("tag", "must be at most 24 characters")));
        }
        return normalized;
    }

    private <D> IdempotentOutcome<D> idempotent(
            UUID userId,
            IdempotencyOperation operation,
            String key,
            String fingerprint,
            Class<D> responseType,
            Supplier<D> action) {
        String idempotencyLock = "idem:lock:" + userId + ":" + operation + ":" + key;
        return distributedLock.withLock(idempotencyLock, LOCK_WAIT, () -> {
            Optional<IdempotencyRecord> existing = idempotencyStore.find(userId, operation, key);
            if (existing.isPresent()) {
                return new IdempotentOutcome<>(replay(existing.get(), fingerprint, responseType), true);
            }
            D data = action.get();
            saveIdempotency(userId, operation, key, fingerprint, data);
            return new IdempotentOutcome<>(data, false);
        });
    }

    private <D> D replay(IdempotencyRecord record, String fingerprint, Class<D> responseType) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.DOC_CONFLICT_001, "同一 Idempotency-Key 已用于不同请求。");
        }
        try {
            return objectMapper.readValue(record.responseJson(), responseType);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.COM_INTERNAL_001, "幂等记录不可用，请稍后重试。", ex);
        }
    }

    private record IdempotentOutcome<D>(D data, boolean replayed) {
    }

    private static DocumentStatus parseStatusFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DocumentStatus.from(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "unknown document status",
                    List.of(FieldViolation.of("status", "must be UPLOADED, PARSING, INDEXED, FAILED or DEAD")));
        }
    }

    private static KeysetCursor parseCursor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        KeysetCursor cursor = KeysetCursor.decode(token).orElseThrow(() -> new BusinessException(
                ErrorCode.COM_VALIDATION_001, "invalid cursor",
                List.of(FieldViolation.of("cursor", "must come from a previous response"))));
        if (!cursor.hasSortKey()) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "invalid cursor",
                    List.of(FieldViolation.of("cursor", "must come from a previous response")));
        }
        return cursor;
    }

    private UploadInitResult initByMd5(UUID userId, UploadInitRequest request) {
        Optional<DocumentRow> existing = documentRepository.findByUserAndMd5(TenantContext.require(), userId, request.md5());
        if (existing.isPresent()) {
            return handleExistingDocument(existing.get(), userId, request);
        }
        return createNewDocument(userId, request);
    }

    private UploadInitResult handleExistingDocument(
            DocumentRow document,
            UUID userId,
            UploadInitRequest request) {
        if (document.deleted()) {
            throw new BusinessException(ErrorCode.DOC_CONFLICT_001, "该文件此前已被删除，暂不能按原 MD5 秒传，请重新上传完整文件。");
        }
        if (document.sizeBytes() != request.sizeBytes()) {
            throw new BusinessException(ErrorCode.DOC_CONFLICT_001, "同一 MD5 的文件大小不一致，请重新计算 MD5。");
        }
        if (document.status() == DocumentStatus.INDEXED) {
            UploadInitData data = new UploadInitData(
                    document.id(),
                    document.id(),
                    DocumentStatus.INDEXED.name(),
                    true,
                    minioProperties.getPartSize(),
                    0,
                    null,
                    List.of(),
                    false);
            return new UploadInitResult(data, false);
        }
        if (document.status() == DocumentStatus.UPLOADED) {
            SessionPlan plan = createSession(document);
            UploadInitData data = new UploadInitData(
                    document.id(),
                    document.id(),
                    DocumentStatus.UPLOADED.name(),
                    false,
                    plan.session().partSize(),
                    plan.session().partCount(),
                    plan.session().expiresAt(),
                    plan.parts(),
                    false);
            return new UploadInitResult(data, true);
        }
        throw new BusinessException(ErrorCode.DOC_CONFLICT_001, "该文件正在解析或已失败，请先查询文档状态。");
    }

    private UploadInitResult createNewDocument(UUID userId, UploadInitRequest request) {
        Instant now = clock.instant();
        UUID documentId = UUID.randomUUID();
        String objectKey = ObjectKeys.source(TenantContext.require(), userId, documentId);
        UploadPlan plan = UploadPlan.of(request.sizeBytes(), minioProperties.getPartSize(), UploadPlan.MAX_PARTS);
        DocumentRow document = new DocumentRow(
                documentId,
                userId,
                request.fileName(),
                null,
                request.contentType(),
                request.sizeBytes(),
                request.md5(),
                objectKey,
                DocumentStatus.UPLOADED,
                0,
                documentProperties.getDefaultMaxRetry(),
                null,
                null,
                documentProperties.getIndexVersion(),
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now);

        try {
            transactionRunner.execute(() -> {
                documentRepository.insert(TenantContext.require(), document);
                return Boolean.TRUE;
            });
        } catch (DuplicateKeyException ex) {
            return documentRepository.findByUserAndMd5(TenantContext.require(), userId, request.md5())
                    .map(existing -> handleExistingDocument(existing, userId, request))
                    .orElseThrow(() -> ex);
        }

        try {
            SessionPlan sessionPlan = createSession(document, plan);
            UploadInitData data = new UploadInitData(
                    document.id(),
                    document.id(),
                    DocumentStatus.UPLOADED.name(),
                    false,
                    sessionPlan.session().partSize(),
                    sessionPlan.session().partCount(),
                    sessionPlan.session().expiresAt(),
                    sessionPlan.parts(),
                    false);
            return new UploadInitResult(data, true);
        } catch (RuntimeException ex) {
            documentRepository.deleteByIdAndUser(TenantContext.require(), document.id(), userId);
            throw ex;
        }
    }

    private SessionPlan createSession(DocumentRow document) {
        UploadPlan plan = UploadPlan.of(
                document.sizeBytes(),
                minioProperties.getPartSize(),
                UploadPlan.MAX_PARTS);
        return createSession(document, plan);
    }

    private SessionPlan createSession(DocumentRow document, UploadPlan plan) {
        Instant now = clock.instant();
        String minioUploadId = objectStorage.createMultipartUpload(document.objectKey());
        try {
            // 重复 init 会覆盖 Redis 中同一 documentId 的旧 session；先把旧分片 abort 掉，
            // 否则旧 minioUploadId 失去引用成为孤儿（C-3）。
            uploadSessionStore.find(document.id()).ifPresent(previous -> {
                if (!minioUploadId.equals(previous.minioUploadId())) {
                    abortQuietly(previous.objectKey(), previous.minioUploadId());
                }
            });
            Instant sessionExpiresAt = now.plus(documentProperties.getSessionTtl());
            UploadSession session = new UploadSession(
                    document.id(),
                    document.userId(),
                    document.objectKey(),
                    minioUploadId,
                    plan.partSize(),
                    plan.partCount(),
                    sessionExpiresAt);
            uploadSessionStore.save(session);

            Instant partExpiresAt = now.plus(minioProperties.getPresignExpiry());
            List<PresignedPart> parts = new ArrayList<>(plan.partCount());
            for (int partNumber = 1; partNumber <= plan.partCount(); partNumber++) {
                String url = objectStorage.presignPart(
                        document.objectKey(),
                        minioUploadId,
                        partNumber,
                        minioProperties.getPresignExpiry());
                parts.add(new PresignedPart(partNumber, url, partExpiresAt));
            }
            return new SessionPlan(session, List.copyOf(parts));
        } catch (RuntimeException ex) {
            abortQuietly(document.objectKey(), minioUploadId);
            throw ex;
        }
    }

    private CompleteUploadResult completeLocked(
            UUID userId,
            UUID uploadId,
            String idempotencyKey,
            String fingerprint,
            CompleteUploadRequest request) {
        DocumentRow document = documentRepository.findByIdAndUser(TenantContext.require(), uploadId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOC_NOT_FOUND_001));
        if (document.status() != DocumentStatus.UPLOADED) {
            CompleteUploadData data = currentCompleteData(document);
            saveIdempotency(
                    userId,
                    IdempotencyOperation.UPLOAD_COMPLETE,
                    idempotencyKey,
                    fingerprint,
                    data);
            return new CompleteUploadResult(data, false);
        }

        UploadSession session = uploadSessionStore.find(uploadId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.DOC_CONFLICT_001,
                        "上传会话已过期，请重新初始化上传。"));
        if (!session.expiresAt().isAfter(clock.instant())) {
            // 过期会话：先 abort MinIO 未完成的分片再删会话，避免悬挂分片累积（C-3）。
            abortQuietly(session.objectKey(), session.minioUploadId());
            deleteSessionQuietly(uploadId);
            throw new BusinessException(ErrorCode.DOC_CONFLICT_001, "上传会话已过期，请重新初始化上传。");
        }
        if (!session.userId().equals(userId)
                || !session.objectKey().equals(document.objectKey())
                || !session.documentId().equals(document.id())) {
            // 会话与文档不匹配：这条分片同样不会被 complete，主动清理
            abortQuietly(session.objectKey(), session.minioUploadId());
            deleteSessionQuietly(uploadId);
            throw new BusinessException(ErrorCode.DOC_CONFLICT_001, "上传会话与文档不匹配，请重新初始化上传。");
        }
        validateCompleteParts(request.parts(), session.partCount());

        List<UploadPart> parts = request.parts().stream()
                .map(part -> new UploadPart(part.partNumber(), part.etag()))
                .toList();
        try {
            objectStorage.completeMultipartUpload(document.objectKey(), session.minioUploadId(), parts);
        } catch (ObjectStorageException ex) {
            throw mapStorageFailure(ex);
        }

        long actualSize;
        String actualMd5;
        try {
            actualSize = objectStorage.size(document.objectKey());
            actualMd5 = objectStorage.md5(document.objectKey());
        } catch (ObjectStorageException ex) {
            throw mapStorageFailure(ex);
        }
        if (actualSize != document.sizeBytes() || !actualMd5.equals(document.md5())) {
            cleanupFailedUpload(document, session);
            throw new BusinessException(ErrorCode.DOC_VALIDATION_004);
        }

        Instant now = clock.instant();
        int updated = transactionRunner.execute(
                () -> documentRepository.markParsing(TenantContext.require(), document.id(), userId, now));
        if (updated == 0) {
            DocumentRow current = documentRepository.findByIdAndUser(TenantContext.require(), uploadId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.DOC_NOT_FOUND_001));
            CompleteUploadData data = currentCompleteData(current);
            saveIdempotency(
                    userId,
                    IdempotencyOperation.UPLOAD_COMPLETE,
                    idempotencyKey,
                    fingerprint,
                    data);
            return new CompleteUploadResult(data, false);
        }

        deleteSessionQuietly(uploadId);
        CompleteUploadData data = new CompleteUploadData(
                document.id(),
                document.id(),
                DocumentStatus.PARSING.name(),
                now,
                document.retryCount(),
                false);
        saveIdempotency(
                userId,
                IdempotencyOperation.UPLOAD_COMPLETE,
                idempotencyKey,
                fingerprint,
                data);
        publishRequestedQuietly(document, document.indexVersion(), document.retryCount(), now);
        return new CompleteUploadResult(data, true);
    }

    private void validateCompleteParts(List<CompletedPart> parts, int expectedPartCount) {
        if (parts == null || parts.size() != expectedPartCount) {
            throw new BusinessException(ErrorCode.DOC_VALIDATION_004, "分片数量与上传会话不一致。");
        }
        for (int index = 0; index < parts.size(); index++) {
            CompletedPart part = parts.get(index);
            if (part == null || part.partNumber() != index + 1) {
                throw new BusinessException(ErrorCode.DOC_VALIDATION_004, "分片必须从 1 开始按升序完整提交。");
            }
            if (part.etag() == null || part.etag().isBlank()) {
                throw new BusinessException(ErrorCode.DOC_VALIDATION_004, "分片 ETag 不能为空。");
            }
        }
    }

    private void cleanupFailedUpload(DocumentRow document, UploadSession session) {
        try {
            objectStorage.remove(document.objectKey());
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to remove invalid upload object: documentId={}", document.id(), ex);
        }
        deleteSessionQuietly(session.documentId());
    }

    private void deleteSessionQuietly(UUID documentId) {
        try {
            uploadSessionStore.delete(documentId);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to delete upload session: documentId={}", documentId, ex);
        }
    }

    private void abortQuietly(String objectKey, String uploadId) {
        try {
            objectStorage.abortMultipartUpload(objectKey, uploadId);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to abort multipart upload: objectKey={}", objectKey, ex);
        }
    }

    private void publishRequestedQuietly(
            DocumentRow document,
            String indexVersion,
            int retryCount,
            Instant now) {
        ParseRequestedEvent event = new ParseRequestedEvent(
                UUID.randomUUID(),
                TraceIdContext.currentOrCreate(),
                document.id(),
                document.userId(),
                TenantContext.require(),
                indexVersion,
                document.objectKey(),
                document.fileName(),
                document.contentType(),
                document.sizeBytes(),
                document.md5(),
                retryCount,
                now);
        try {
            documentEventPublisher.publishParseRequested(event);
        } catch (RuntimeException ex) {
            LOGGER.error(
                    "parse.requested publish failed; reconciliation will retry: traceId={}, documentId={}",
                    event.traceId(),
                    document.id(),
                    ex);
        }
    }

    private void publishVectorChangeQuietly(VectorChangeRequestedEvent event) {
        try {
            documentEventPublisher.publishVectorChangeRequested(event);
        } catch (RuntimeException ex) {
            LOGGER.error(
                    "vector.change.requested publish failed; deletion sweep will retry:"
                            + " traceId={}, documentId={}, operation={}",
                    event.traceId(),
                    event.documentId(),
                    event.operation(),
                    ex);
        }
    }

    private CompleteUploadData currentCompleteData(DocumentRow document) {
        Instant parseRequestedAt = document.parsingStartedAt() == null
                ? document.updatedAt()
                : document.parsingStartedAt();
        return new CompleteUploadData(
                document.id(),
                document.id(),
                document.status().name(),
                parseRequestedAt,
                document.retryCount(),
                false);
    }

    private UploadInitResult replayInit(IdempotencyRecord record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.DOC_CONFLICT_001, "同一 Idempotency-Key 已用于不同请求。");
        }
        try {
            UploadInitData data = objectMapper.readValue(record.responseJson(), UploadInitData.class);
            return new UploadInitResult(data.withIdempotentReplay(), false);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.COM_INTERNAL_001, "幂等记录不可用，请稍后重试。", ex);
        }
    }

    private CompleteUploadResult replayComplete(IdempotencyRecord record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.DOC_CONFLICT_001, "同一 Idempotency-Key 已用于不同请求。");
        }
        try {
            CompleteUploadData data = objectMapper.readValue(record.responseJson(), CompleteUploadData.class);
            return new CompleteUploadResult(data.withIdempotentReplay(), false);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.COM_INTERNAL_001, "幂等记录不可用，请稍后重试。", ex);
        }
    }

    private void saveIdempotency(
            UUID userId,
            IdempotencyOperation operation,
            String key,
            String fingerprint,
            Object response) {
        try {
            String responseJson = objectMapper.writeValueAsString(response);
            idempotencyStore.save(
                    userId,
                    operation,
                    key,
                    new IdempotencyRecord(fingerprint, responseJson, clock.instant()));
        } catch (JsonProcessingException ex) {
            LOGGER.error("Unable to serialize idempotency response: operation={}", operation, ex);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to save idempotency record: operation={}", operation, ex);
        }
    }

    private String initFingerprint(UploadInitRequest request) {
        return Fingerprints.sha256(
                request.fileName() + "\n"
                        + request.sizeBytes() + "\n"
                        + request.contentType() + "\n"
                        + request.md5());
    }

    private String completeFingerprint(CompleteUploadRequest request) {
        StringBuilder value = new StringBuilder();
        if (request.parts() != null) {
            for (CompletedPart part : request.parts()) {
                if (part != null) {
                    value.append(part.partNumber()).append(':').append(part.etag()).append('|');
                }
            }
        }
        return Fingerprints.sha256(value.toString());
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || !key.matches(IDEMPOTENCY_KEY_PATTERN)) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of(
                            "Idempotency-Key",
                            "must be 8-64 characters of letters, digits, dot, underscore, colon or hyphen")));
        }
    }

    private void validateInitRequest(UploadInitRequest request) {
        String fileName = request.fileName();
        if (fileName == null || fileName.isBlank() || fileName.length() > 255
                || fileName.indexOf('/') >= 0
                || fileName.indexOf('\\') >= 0
                || fileName.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("fileName", "must be a file name without path or control characters")));
        }
        Long sizeBytes = request.sizeBytes();
        if (sizeBytes == null || sizeBytes < 1) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("sizeBytes", "must be greater than 0")));
        }
        if (sizeBytes > MAX_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.DOC_VALIDATION_001);
        }
        if (request.contentType() == null || !ALLOWED_CONTENT_TYPES.contains(request.contentType())) {
            throw new BusinessException(ErrorCode.DOC_VALIDATION_002);
        }
        String md5 = request.md5();
        if (md5 == null || !md5.matches("^[a-f0-9]{32}$")) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("md5", "must be 32 lowercase hexadecimal characters")));
        }
    }

    private BusinessException mapStorageFailure(ObjectStorageException exception) {
        if ("NoSuchUpload".equals(exception.getCode()) || "NoSuchKey".equals(exception.getCode())) {
            return new BusinessException(ErrorCode.DOC_CONFLICT_001, "上传会话已失效，请重新初始化上传。", exception);
        }
        if (exception.isClientError()) {
            return new BusinessException(ErrorCode.DOC_VALIDATION_004, null, exception);
        }
        return new BusinessException(ErrorCode.COM_INTERNAL_001, "对象存储暂不可用，请稍后重试。", exception);
    }

    private record SessionPlan(UploadSession session, List<PresignedPart> parts) {
    }
}
