package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.domain.DocumentRecord;
import com.docqa.agentgateway.dto.AuthorizedDocument;
import com.docqa.agentgateway.repository.DocumentScopeRepository;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentScopeServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private DocumentScopeRepository repository;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rejectsEmptyScopeWithoutQuerying() {
        DocumentScopeService service = new DocumentScopeService(repository);

        assertThatThrownBy(() -> service.resolve(USER_ID, List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_VALIDATION_001);
        verify(repository, never()).findByUserAndIds(any(), any(), any());
    }

    @Test
    void rejectsDuplicateScope() {
        DocumentScopeService service = new DocumentScopeService(repository);

        assertThatThrownBy(() -> service.resolve(USER_ID, List.of(DOCUMENT_ID, DOCUMENT_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_VALIDATION_001);
    }

    @Test
    void rejectsForeignOrMissingDocument() {
        DocumentScopeService service = new DocumentScopeService(repository);
        when(repository.findByUserAndIds(TENANT_ID, USER_ID, List.of(DOCUMENT_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolve(USER_ID, List.of(DOCUMENT_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DOC_NOT_FOUND_001);
    }

    @Test
    void rejectsUnindexedDocument() {
        DocumentScopeService service = new DocumentScopeService(repository);
        when(repository.findByUserAndIds(TENANT_ID, USER_ID, List.of(DOCUMENT_ID)))
                .thenReturn(List.of(document(DOCUMENT_ID, "PARSING")));

        assertThatThrownBy(() -> service.resolve(USER_ID, List.of(DOCUMENT_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_VALIDATION_002);
    }

    @Test
    void returnsAuthorizedDocumentsInRequestOrder() {
        DocumentScopeService service = new DocumentScopeService(repository);
        when(repository.findByUserAndIds(TENANT_ID, USER_ID, List.of(OTHER_ID, DOCUMENT_ID)))
                .thenReturn(List.of(
                        document(DOCUMENT_ID, "INDEXED"),
                        document(OTHER_ID, "INDEXED")));

        List<AuthorizedDocument> resolved = service.resolve(USER_ID, List.of(OTHER_ID, DOCUMENT_ID));

        assertThat(resolved).containsExactly(
                new AuthorizedDocument(OTHER_ID, "v1"),
                new AuthorizedDocument(DOCUMENT_ID, "v1"));
    }

    @Test
    void assertOwnedSkipsEmptyScope() {
        DocumentScopeService service = new DocumentScopeService(repository);

        service.assertOwned(USER_ID, List.of());

        verify(repository, never()).findByUserAndIds(any(), any(), any());
    }

    @Test
    void assertOwnedAcceptsNonIndexedDocument() {
        DocumentScopeService service = new DocumentScopeService(repository);
        when(repository.findByUserAndIds(TENANT_ID, USER_ID, List.of(DOCUMENT_ID)))
                .thenReturn(List.of(document(DOCUMENT_ID, "UPLOADED")));

        service.assertOwned(USER_ID, List.of(DOCUMENT_ID));
    }

    @Test
    void assertOwnedRejectsForeignDocument() {
        DocumentScopeService service = new DocumentScopeService(repository);
        when(repository.findByUserAndIds(TENANT_ID, USER_ID, List.of(DOCUMENT_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> service.assertOwned(USER_ID, List.of(DOCUMENT_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DOC_NOT_FOUND_001);
    }

    private DocumentRecord document(UUID id, String status) {
        return new DocumentRecord(id, status, "v1");
    }
}
