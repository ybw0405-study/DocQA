package com.docqa.document.controller;

import com.docqa.common.api.ApiResponse;
import com.docqa.document.dto.CompleteUploadData;
import com.docqa.document.dto.CompleteUploadRequest;
import com.docqa.document.dto.DocumentListData;
import com.docqa.document.dto.DocumentRenameRequest;
import com.docqa.document.dto.DocumentStatusData;
import com.docqa.document.dto.DocumentTagsUpdateRequest;
import com.docqa.document.dto.UploadInitData;
import com.docqa.document.dto.UploadInitRequest;
import com.docqa.document.service.CompleteUploadResult;
import com.docqa.document.service.DocumentCommandResult;
import com.docqa.document.service.DocumentService;
import com.docqa.document.service.UploadInitResult;
import com.docqa.document.web.JwtAuthenticationFilter;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public ApiResponse<DocumentListData> list(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "tag", required = false) String tag,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(documentService.list(userId, status, q, tag, cursor, limit));
    }

    @PostMapping("/upload/init")
    public ResponseEntity<ApiResponse<UploadInitData>> initUpload(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody UploadInitRequest request) {
        UploadInitResult result = documentService.init(userId, idempotencyKey, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.data()));
    }

    @PostMapping("/{uploadId}/complete")
    public ResponseEntity<ApiResponse<CompleteUploadData>> completeUpload(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID uploadId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CompleteUploadRequest request) {
        CompleteUploadResult result = documentService.complete(userId, uploadId, idempotencyKey, request);
        HttpStatus status = result.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.data()));
    }

    @GetMapping("/{documentId}/status")
    public ApiResponse<DocumentStatusData> getStatus(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID documentId) {
        return ApiResponse.ok(documentService.status(userId, documentId));
    }

    @PatchMapping("/{documentId}")
    public ApiResponse<DocumentStatusData> renameDocument(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID documentId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody DocumentRenameRequest request) {
        return ApiResponse.ok(documentService.rename(userId, documentId, idempotencyKey, request));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID documentId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
        documentService.delete(userId, documentId, idempotencyKey);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{documentId}/tags")
    public ApiResponse<DocumentStatusData> replaceDocumentTags(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID documentId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody DocumentTagsUpdateRequest request) {
        return ApiResponse.ok(documentService.replaceTags(userId, documentId, idempotencyKey, request));
    }

    @PostMapping("/{documentId}/retry")
    public ResponseEntity<ApiResponse<DocumentStatusData>> retryDocumentParse(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID documentId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
        return commandResponse(documentService.retryParse(userId, documentId, idempotencyKey));
    }

    @PostMapping("/{documentId}/reindex")
    public ResponseEntity<ApiResponse<DocumentStatusData>> reindexDocument(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID documentId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
        return commandResponse(documentService.reindex(userId, documentId, idempotencyKey));
    }

    private static ResponseEntity<ApiResponse<DocumentStatusData>> commandResponse(
            DocumentCommandResult result) {
        HttpStatus status = result.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.data()));
    }
}
