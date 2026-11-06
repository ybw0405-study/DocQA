package com.docqa.agentgateway.service;

import java.time.Instant;
import java.util.UUID;

public record KillSwitchMeta(Instant updatedAt, UUID updatedBy) {
}
