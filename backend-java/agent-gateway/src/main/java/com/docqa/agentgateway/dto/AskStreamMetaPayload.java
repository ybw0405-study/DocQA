package com.docqa.agentgateway.dto;

import java.util.UUID;

/** SSE meta 帧载荷：messageId 在流首即固定，客户端本地消息行从第一刻起即可与服务端历史对账。 */
public record AskStreamMetaPayload(
        UUID conversationId,
        UUID messageId,
        String traceId,
        boolean cacheHit) {
}
