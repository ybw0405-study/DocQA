package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条长期记忆的对外形态。文本是数据面**掩码后**的事实，网关与管理页都不做任何还原；
 * key 是数据面生成的 UUID，删除时按它定位，租户与用户谓词由服务端附加。
 */
public record MemoryItem(UUID key, String text, Instant createdAt) {
}