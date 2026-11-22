package com.docqa.agentgateway.domain;

import java.time.Instant;
import java.util.UUID;

/** 审计 outbox relay 的持久化游标（keyset 位置）。 */
public record RelayCursor(Instant recordedAt, UUID id) {
}
