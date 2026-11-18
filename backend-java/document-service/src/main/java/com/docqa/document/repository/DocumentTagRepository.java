package com.docqa.document.repository;

import com.docqa.document.domain.TagName;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DocumentTagRepository {

    Map<String, UUID> resolveOrCreate(UUID tenantId, UUID userId, List<TagName> tags, Instant now);

    void replaceLinks(UUID tenantId, UUID documentId, List<UUID> tagIds, Instant now);

    List<String> findNames(UUID tenantId, UUID documentId);

    Map<UUID, List<String>> findNamesByDocuments(UUID tenantId, List<UUID> documentIds);
}
