package com.docqa.agentgateway.domain;

import java.util.UUID;

public record DocumentRecord(UUID id, String status, String indexVersion) {
}
