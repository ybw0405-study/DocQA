package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.ConversationListItemData;
import com.docqa.agentgateway.dto.ConversationListData;
import com.docqa.agentgateway.dto.ConversationListRow;
import com.docqa.agentgateway.repository.ConversationRepository;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ConversationListService {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationRepository conversationRepository;

    public ConversationListService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    public ConversationListData list(UUID userId, String cursorToken, int limit) {
        if (limit < MIN_PAGE_SIZE || limit > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "limit out of range",
                    List.of(FieldViolation.of("limit", "must be between 1 and 100")));
        }
        KeysetCursor cursor = parseCursor(cursorToken);
        List<ConversationListRow> rows = conversationRepository.findUserPage(
                userId,
                cursor == null ? null : cursor.sortKey(),
                cursor == null ? null : cursor.id(),
                cursor != null && !cursor.hasSortKey(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<ConversationListRow> page = hasMore ? rows.subList(0, limit) : rows;
        List<ConversationListItemData> items = page.stream()
                .map(row -> new ConversationListItemData(
                        row.id(),
                        row.title(),
                        row.documentScopeIds(),
                        row.lastMessageAt(),
                        row.createdAt()))
                .toList();
        String nextCursor = hasMore
                ? KeysetCursor.of(page.get(page.size() - 1).lastMessageAt(), page.get(page.size() - 1).id()).encode()
                : null;
        return new ConversationListData(items, nextCursor, limit);
    }

    private static KeysetCursor parseCursor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return KeysetCursor.decode(token).orElseThrow(() -> new BusinessException(
                ErrorCode.COM_VALIDATION_001, "invalid cursor",
                List.of(FieldViolation.of("cursor", "must come from a previous response"))));
    }
}
