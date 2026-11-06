package com.docqa.agentgateway.service;

import com.docqa.agentgateway.domain.DocumentRecord;
import com.docqa.agentgateway.dto.AuthorizedDocument;
import com.docqa.agentgateway.repository.DocumentScopeRepository;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DocumentScopeService {

    private final DocumentScopeRepository documentScopeRepository;

    public DocumentScopeService(DocumentScopeRepository documentScopeRepository) {
        this.documentScopeRepository = documentScopeRepository;
    }

    public void assertOwned(UUID userId, List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        List<DocumentRecord> records = documentScopeRepository.findByUserAndIds(userId, documentIds);
        if (records.size() != documentIds.size()) {
            throw new BusinessException(ErrorCode.DOC_NOT_FOUND_001);
        }
    }

    public List<AuthorizedDocument> resolve(UUID userId, List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("documentIds", "must contain at least one document")));
        }
        if (new LinkedHashSet<>(documentIds).size() != documentIds.size()) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("documentIds", "must not contain duplicates")));
        }
        List<DocumentRecord> records = documentScopeRepository.findByUserAndIds(userId, documentIds);
        Map<UUID, DocumentRecord> byId = records.stream()
                .collect(Collectors.toMap(DocumentRecord::id, Function.identity()));
        if (byId.size() != documentIds.size()) {
            throw new BusinessException(ErrorCode.DOC_NOT_FOUND_001);
        }
        return documentIds.stream().map(id -> {
            DocumentRecord record = byId.get(id);
            if (!"INDEXED".equals(record.status())) {
                throw new BusinessException(
                        ErrorCode.AGT_VALIDATION_002,
                        "Document is not indexed: " + id);
            }
            return new AuthorizedDocument(record.id(), record.indexVersion());
        }).toList();
    }
}
