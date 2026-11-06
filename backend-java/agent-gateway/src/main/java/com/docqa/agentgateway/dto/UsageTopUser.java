package com.docqa.agentgateway.dto;

import java.util.UUID;

/** 用量最高的用户；`username` 在身份行缺失时为 null，不影响计数。 */
public record UsageTopUser(UUID userId, String username, long questions, long tokens) {
}
