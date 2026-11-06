package com.docqa.document.repository;

import com.docqa.document.domain.TagName;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DocumentTagRepository {

    Map<String, UUID> resolveOrCreate(UUID userId, List<TagName> tags, Instant now);

    void replaceLinks(UUID documentId, List<UUID> tagIds, Instant now);

    List<String> findNames(UUID documentId);

    Map<UUID, List<String>> findNamesByDocuments(List<UUID> documentIds);
}
