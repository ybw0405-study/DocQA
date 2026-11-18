package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.ConversationData;
import com.docqa.agentgateway.dto.CreateConversationRequest;
import com.docqa.agentgateway.repository.ConversationRepository;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {

    public static final String DEFAULT_TITLE = "新会话";

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationService.class);

    private final Clock clock;
    private final ConversationRepository conversationRepository;
    private final ConversationCreationStore creationStore;
    private final DocumentScopeService documentScopeService;
    private final RateLimiter rateLimiter;

    public ConversationService(
            Clock clock,
            ConversationRepository conversationRepository,
            ConversationCreationStore creationStore,
            DocumentScopeService documentScopeService,
            RateLimiter rateLimiter) {
        this.clock = clock;
        this.conversationRepository = conversationRepository;
        this.creationStore = creationStore;
        this.documentScopeService = documentScopeService;
        this.rateLimiter = rateLimiter;
    }

    public ConversationData create(UUID userId, String idempotencyKey, CreateConversationRequest request) {
        UUID tenantId = TenantContext.require();
        String key = IdempotencyKeys.validate(idempotencyKey);
        String title = normalizeTitle(request == null ? null : request.title());
        List<UUID> scope = normalizeScope(request == null ? null : request.documentScopeIds());
        rateLimiter.check(userId);

        String fingerprint = fingerprint(userId, title, scope);
        Optional<ConversationCreationRecord> existing = creationStore.find(userId, key);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        if (!scope.isEmpty()) {
            documentScopeService.assertOwned(userId, scope);
        }
        if (!creationStore.tryStart(userId, key, fingerprint, clock.instant())) {
            return replayOrConflict(
                    creationStore.find(userId, key)
                            .orElseThrow(() -> new BusinessException(ErrorCode.AGT_CONFLICT_002)),
                    fingerprint);
        }

        UUID conversationId = UUID.randomUUID();
        Instant now = clock.instant();
        if (!conversationRepository.createOwned(
                tenantId, conversationId, userId, title, scope, now)) {
            creationStore.clear(userId, key);
            throw new BusinessException(ErrorCode.COM_INTERNAL_001, "Unable to create conversation");
        }
        ConversationData data = new ConversationData(conversationId, title, scope, false, now);
        try {
            creationStore.complete(userId, key, fingerprint, data, clock.instant());
        } catch (RuntimeException ex) {
            LOGGER.error("Unable to complete conversation creation record: conversationId={}", conversationId);
        }
        return data;
    }

    public void requireAccessible(UUID conversationId, UUID userId) {
        UUID tenantId = TenantContext.require();
        if (conversationRepository.existsByIdAndUser(tenantId, conversationId, userId)) {
            return;
        }
        if (conversationRepository.existsById(tenantId, conversationId)) {
            throw new BusinessException(ErrorCode.AGT_NOT_FOUND_001);
        }
    }

    public void ensureOwned(UUID conversationId, UUID userId, List<UUID> documentScopeIds) {
        UUID tenantId = TenantContext.require();
        if (conversationRepository.existsByIdAndUser(tenantId, conversationId, userId)) {
            return;
        }
        if (!conversationRepository.createOwned(
                tenantId, conversationId, userId, DEFAULT_TITLE, documentScopeIds, clock.instant())) {
            if (conversationRepository.existsByIdAndUser(tenantId, conversationId, userId)) {
                return;
            }
            throw new BusinessException(ErrorCode.AGT_NOT_FOUND_001);
        }
    }

    private ConversationData replayOrConflict(ConversationCreationRecord record, String fingerprint) {
        if (record.status() == IdempotencyStatus.COMPLETED
                && fingerprint.equals(record.fingerprint())
                && record.response() != null) {
            ConversationData original = record.response();
            return new ConversationData(
                    original.conversationId(),
                    original.title(),
                    original.documentScopeIds(),
                    true,
                    original.createdAt());
        }
        String detail = record.status() == IdempotencyStatus.IN_PROGRESS
                ? "The same Idempotency-Key is still in progress"
                : "The same Idempotency-Key was used with a different request";
        throw new BusinessException(ErrorCode.AGT_CONFLICT_002, detail);
    }

    private String normalizeTitle(String title) {
        if (title == null) {
            return DEFAULT_TITLE;
        }
        String value = title.trim();
        if (value.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("title", "must not be blank")));
        }
        if (value.length() > 200) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("title", "must be at most 200 characters")));
        }
        return value;
    }

    private List<UUID> normalizeScope(List<UUID> documentScopeIds) {
        if (documentScopeIds == null || documentScopeIds.isEmpty()) {
            return List.of();
        }
        if (documentScopeIds.size() > 20) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("documentScopeIds", "must contain at most 20 documents")));
        }
        Set<UUID> unique = new LinkedHashSet<>(documentScopeIds);
        if (unique.size() != documentScopeIds.size()) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("documentScopeIds", "must not contain duplicates")));
        }
        return List.copyOf(documentScopeIds);
    }

    private String fingerprint(UUID userId, String title, List<UUID> scope) {
        String scopeValue = scope.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        return Hashing.sha256(userId + "|CREATE_CONVERSATION|" + title + "|" + scopeValue);
    }
}
