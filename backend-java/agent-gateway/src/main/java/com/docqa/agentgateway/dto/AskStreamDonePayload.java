package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** SSE done 帧载荷：完整 AskData 同构字段 + traceId，字段真源以本帧为准。 */
public record AskStreamDonePayload(
        UUID conversationId,
        UUID messageId,
        String answer,
        List<Citation> citations,
        double confidence,
        String budgetStatus,
        int tokenUsed,
        int toolCallsMade,
        int roundsUsed,
        boolean cacheHit,
        boolean idempotentReplay,
        Instant createdAt,
        String traceId) {

    public static AskStreamDonePayload of(AskData data, String traceId) {
        return new AskStreamDonePayload(
                data.conversationId(),
                data.messageId(),
                data.answer(),
                data.citations(),
                data.confidence(),
                data.budgetStatus(),
                data.tokenUsed(),
                data.toolCallsMade(),
                data.roundsUsed(),
                data.cacheHit(),
                data.idempotentReplay(),
                data.createdAt(),
                traceId);
    }
}
