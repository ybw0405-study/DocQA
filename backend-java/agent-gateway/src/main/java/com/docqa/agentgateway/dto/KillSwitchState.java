package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.UUID;

/** Kill Switch 的当前状态；updatedAt/updatedBy 缺失表示元数据尚未写入，不改变 active 的判读。 */
public record KillSwitchState(boolean active, Instant updatedAt, UUID updatedBy) {
}
