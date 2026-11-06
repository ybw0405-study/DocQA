package com.docqa.document.repository;

import com.docqa.document.domain.ChunkEntry;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ChunkRepository {

    void upsertAll(List<ChunkEntry> chunks);

    long count(UUID documentId, String indexVersion);

    Map<UUID, Long> countByDocuments(List<UUID> documentIds);

    int deleteByDocument(UUID documentId);

    int deleteByVersion(UUID documentId, String indexVersion);
}
