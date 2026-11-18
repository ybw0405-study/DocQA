package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.domain.DocumentRecord;
import java.util.List;
import java.util.UUID;

public interface DocumentScopeRepository {

    List<DocumentRecord> findByUserAndIds(UUID tenantId, UUID userId, List<UUID> documentIds);
}
