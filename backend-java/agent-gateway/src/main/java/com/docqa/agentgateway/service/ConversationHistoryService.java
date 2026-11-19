package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.MessageHistoryData;
import com.docqa.agentgateway.dto.MessageHistoryItemData;
import com.docqa.agentgateway.dto.MessageHistoryRow;
import com.docqa.agentgateway.repository.ConversationRepository;
import com.docqa.agentgateway.repository.MessageRepository;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ConversationHistoryService {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final PiiMasker piiMasker;

    public ConversationHistoryService(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            PiiMasker piiMasker) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.piiMasker = piiMasker;
    }

    public MessageHistoryData list(UUID userId, UUID conversationId, String cursorToken, int limit) {
        UUID tenantId = TenantContext.require();
        if (limit < MIN_PAGE_SIZE || limit > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "limit out of range",
                    List.of(FieldViolation.of("limit", "must be between 1 and 100")));
        }
        KeysetCursor cursor = parseCursor(cursorToken);
        if (!conversationRepository.existsByIdAndUser(tenantId, conversationId, userId)) {
            throw new BusinessException(ErrorCode.AGT_NOT_FOUND_001);
        }
        List<MessageHistoryRow> rows = messageRepository.findPage(
                tenantId,
                conversationId,
                cursor == null ? null : cursor.sortKey(),
                cursor == null ? null : cursor.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<MessageHistoryRow> page = hasMore ? rows.subList(0, limit) : rows;
        List<MessageHistoryItemData> items = page.stream()
                .map(row -> new MessageHistoryItemData(
                        row.id(),
                        row.role(),
                        piiMasker.mask(row.content()).masked(),
                        row.tokenCount(),
                        row.traceId(),
                        row.createdAt()))
                .toList();
        String nextCursor = hasMore
                ? KeysetCursor.of(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()).encode()
                : null;
        return new MessageHistoryData(conversationId, items, nextCursor, limit);
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
}
