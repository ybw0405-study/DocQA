package com.docqa.document.repository;

import com.docqa.document.domain.ChunkEntry;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ChunkRepository {

    void upsertAll(UUID tenantId, List<ChunkEntry> chunks);

    long count(UUID tenantId, UUID documentId, String indexVersion);

    Map<UUID, Long> countByDocuments(UUID tenantId, List<UUID> documentIds);

    int deleteByDocument(UUID tenantId, UUID documentId);

    int deleteByVersion(UUID tenantId, UUID documentId, String indexVersion);
}
