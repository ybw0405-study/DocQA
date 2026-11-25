package com.docqa.document.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.TraceIdFilter;
import com.docqa.document.dto.CompleteUploadData;
import com.docqa.document.dto.DocumentListData;
import com.docqa.document.dto.DocumentListItemData;
import com.docqa.document.dto.DocumentRenameRequest;
import com.docqa.document.dto.DocumentStatusData;
import com.docqa.document.dto.DocumentTagsUpdateRequest;
import com.docqa.document.dto.PresignedPart;
import com.docqa.document.dto.UploadInitData;
import com.docqa.document.service.CompleteUploadResult;
import com.docqa.document.service.DocumentCommandResult;
import com.docqa.document.service.DocumentService;
import com.docqa.document.service.UploadInitResult;
import com.docqa.document.web.JwtAuthenticationFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String IDEMPOTENCY_KEY = "idem-key-0001";
    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private DocumentService documentService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @BeforeEach
    void setUp() {
        objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);
        converter.setSupportedMediaTypes(
                List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(documentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .setMessageConverters(converter)
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void initReturnsCreated() throws Exception {
        UploadInitData data = new UploadInitData(
                DOCUMENT_ID,
                DOCUMENT_ID,
                "UPLOADED",
                false,
                5242880,
                2,
                NOW.plusSeconds(2700),
                List.of(
                        new PresignedPart(1, "https://minio/part-1", NOW.plusSeconds(900)),
                        new PresignedPart(2, "https://minio/part-2", NOW.plusSeconds(900))),
                false);
        when(documentService.init(eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new UploadInitResult(data, true));

        mockMvc.perform(post("/api/v1/documents/upload/init")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"notes.pdf","sizeBytes":10485760,\
                                "contentType":"application/pdf","md5":"d41d8cd98f00b204e9800998ecf8427e"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.parts.length()").value(2))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void initReplayReturnsOk() throws Exception {
        UploadInitData data = new UploadInitData(
                DOCUMENT_ID,
                DOCUMENT_ID,
                "INDEXED",
                true,
                5242880,
                0,
                null,
                List.of(),
                true);
        when(documentService.init(eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new UploadInitResult(data, false));

        mockMvc.perform(post("/api/v1/documents/upload/init")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"notes.pdf","sizeBytes":5242880,\
                                "contentType":"application/pdf","md5":"d41d8cd98f00b204e9800998ecf8427e"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instantUpload").value(true))
                .andExpect(jsonPath("$.data.partCount").value(0));
    }

    @Test
    void completeReturnsAccepted() throws Exception {
        CompleteUploadData data = new CompleteUploadData(
                DOCUMENT_ID,
                DOCUMENT_ID,
                "PARSING",
                NOW,
                0,
                false);
        when(documentService.complete(eq(USER_ID), eq(DOCUMENT_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new CompleteUploadResult(data, true));

        mockMvc.perform(post("/api/v1/documents/{uploadId}/complete", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parts":[{"partNumber":1,"etag":"etag-1"}]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PARSING"))
                .andExpect(jsonPath("$.data.retryCount").value(0));
    }

    @Test
    void statusReturnsEnvelopeWithTheRebuildAndReconciliationFields() throws Exception {
        when(documentService.status(USER_ID, DOCUMENT_ID)).thenReturn(statusData("INDEXED"));

        mockMvc.perform(get("/api/v1/documents/{documentId}/status", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.data.title").value("高等数学·第三章笔记"))
                .andExpect(jsonPath("$.data.tags[0]").value("考研数学"))
                .andExpect(jsonPath("$.data.chunkCount").value(42))
                .andExpect(jsonPath("$.data.pendingIndexVersion").value("v2"))
                .andExpect(jsonPath("$.data.vectorRows").value(42))
                .andExpect(jsonPath("$.data.vectorConsistency").value("MATCHED"));
    }

    @Test
    void renameReturnsTheUpdatedEnvelope() throws Exception {
        when(documentService.rename(eq(USER_ID), eq(DOCUMENT_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(statusData("INDEXED"));

        mockMvc.perform(patch("/api/v1/documents/{documentId}", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"高等数学·第三章笔记"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.title").value("高等数学·第三章笔记"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void renameWithABlankTitleReturnsAValidationProblem() throws Exception {
        mockMvc.perform(patch("/api/v1/documents/{documentId}", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("title"));
        verify(documentService, never()).rename(any(), any(), any(), any());
    }

    @Test
    void deleteReturnsNoContentWithoutABody() throws Exception {
        mockMvc.perform(delete("/api/v1/documents/{documentId}", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(documentService).delete(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);
    }

    @Test
    void deleteOfADocumentStillParsingConflicts() throws Exception {
        doThrow(new BusinessException(
                        ErrorCode.DOC_CONFLICT_001, "文档正在解析中，请等待解析结束后再删除。"))
                .when(documentService).delete(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);

        mockMvc.perform(delete("/api/v1/documents/{documentId}", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC-CONFLICT-001"));
    }

    @Test
    void replaceTagsReturnsTheEnvelopeWithTheNewTagList() throws Exception {
        when(documentService.replaceTags(eq(USER_ID), eq(DOCUMENT_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(statusData("INDEXED"));

        mockMvc.perform(put("/api/v1/documents/{documentId}/tags", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tags":["考研数学"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[0]").value("考研数学"));
    }

    @Test
    void replaceTagsAcceptsAnExplicitEmptyListToClearEveryTag() throws Exception {
        when(documentService.replaceTags(eq(USER_ID), eq(DOCUMENT_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(statusData("INDEXED"));

        mockMvc.perform(put("/api/v1/documents/{documentId}/tags", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tags":[]}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<DocumentTagsUpdateRequest> captor =
                ArgumentCaptor.forClass(DocumentTagsUpdateRequest.class);
        verify(documentService).replaceTags(eq(USER_ID), eq(DOCUMENT_ID), eq(IDEMPOTENCY_KEY), captor.capture());
        assertThat(captor.getValue().tags()).isEmpty();
    }

    @Test
    void retryReturnsAcceptedForAFreshCommand() throws Exception {
        when(documentService.retryParse(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY))
                .thenReturn(new DocumentCommandResult(statusData("PARSING"), true));

        mockMvc.perform(post("/api/v1/documents/{documentId}/retry", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PARSING"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void retryReplayReturnsOk() throws Exception {
        when(documentService.retryParse(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY))
                .thenReturn(new DocumentCommandResult(statusData("PARSING"), false));

        mockMvc.perform(post("/api/v1/documents/{documentId}/retry", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARSING"));
    }

    @Test
    void reindexReturnsAcceptedAndKeepsTheDocumentQueryable() throws Exception {
        when(documentService.reindex(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY))
                .thenReturn(new DocumentCommandResult(statusData("INDEXED"), true));

        mockMvc.perform(post("/api/v1/documents/{documentId}/reindex", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("INDEXED"))
                .andExpect(jsonPath("$.data.indexVersion").value("v1"))
                .andExpect(jsonPath("$.data.pendingIndexVersion").value("v2"));
    }

    @Test
    void reindexReplayReturnsOk() throws Exception {
        when(documentService.reindex(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY))
                .thenReturn(new DocumentCommandResult(statusData("INDEXED"), false));

        mockMvc.perform(post("/api/v1/documents/{documentId}/reindex", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void commandsWithoutAnIdempotencyKeyAreRejectedBeforeTheServiceIsCalled() throws Exception {
        mockMvc.perform(patch("/api/v1/documents/{documentId}", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"新标题"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));

        mockMvc.perform(delete("/api/v1/documents/{documentId}", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/documents/{documentId}/tags", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tags":[]}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/documents/{documentId}/retry", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/documents/{documentId}/reindex", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isBadRequest());

        verify(documentService, never()).rename(any(), any(), any(), any());
        verify(documentService, never()).replaceTags(any(), any(), any(), any());
        verify(documentService, never()).delete(any(), any(), any());
        verify(documentService, never()).retryParse(any(), any(), any());
        verify(documentService, never()).reindex(any(), any(), any());
    }

    @Test
    void missingIdempotencyKeyReturnsValidationProblem() throws Exception {
        mockMvc.perform(post("/api/v1/documents/upload/init")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"notes.pdf","sizeBytes":5242880,\
                                "contentType":"application/pdf","md5":"d41d8cd98f00b204e9800998ecf8427e"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void documentNotFoundReturnsProblem() throws Exception {
        when(documentService.status(USER_ID, DOCUMENT_ID))
                .thenThrow(new BusinessException(ErrorCode.DOC_NOT_FOUND_001));

        mockMvc.perform(get("/api/v1/documents/{documentId}/status", DOCUMENT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DOC-NOT_FOUND-001"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void listReturnsEnvelopeWithoutTheFreeFormErrorMessage() throws Exception {
        DocumentListData data = new DocumentListData(
                List.of(new DocumentListItemData(
                        DOCUMENT_ID, "calculus-notes.pdf", "高等数学·第三章笔记", List.of("考研数学"),
                        "application/pdf", 10_485_760L, "FAILED", 1, 3, "DOC-PARSE-001", "v1", 0L,
                        null, NOW, NOW)),
                "cursor-token",
                20);
        when(documentService.list(USER_ID, null, null, null, null, 20)).thenReturn(data);

        mockMvc.perform(get("/api/v1/documents")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.limit").value(20))
                .andExpect(jsonPath("$.data.nextCursor").value("cursor-token"))
                .andExpect(jsonPath("$.data.items[0].documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].title").value("高等数学·第三章笔记"))
                .andExpect(jsonPath("$.data.items[0].tags[0]").value("考研数学"))
                .andExpect(jsonPath("$.data.items[0].errorCode").value("DOC-PARSE-001"))
                .andExpect(jsonPath("$.data.items[0].errorMessage").doesNotExist())
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void listPassesStatusSearchTagCursorAndLimitToTheService() throws Exception {
        when(documentService.list(eq(USER_ID), eq("FAILED"), eq("高数"), eq("考研数学"), eq("cursor-token"), eq(50)))
                .thenReturn(new DocumentListData(List.of(), null, 50));

        mockMvc.perform(get("/api/v1/documents")
                        .param("status", "FAILED")
                        .param("q", "高数")
                        .param("tag", "考研数学")
                        .param("cursor", "cursor-token")
                        .param("limit", "50")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void listRejectsLimitOutsideTheContractRange() throws Exception {
        when(documentService.list(eq(USER_ID), isNull(), isNull(), isNull(), isNull(), eq(101)))
                .thenThrow(new BusinessException(ErrorCode.COM_VALIDATION_001, "limit out of range"));

        mockMvc.perform(get("/api/v1/documents")
                        .param("limit", "101")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    private static DocumentStatusData statusData(String status) {
        return new DocumentStatusData(
                DOCUMENT_ID,
                "calculus-notes.pdf",
                "高等数学·第三章笔记",
                List.of("考研数学"),
                status,
                0,
                3,
                null,
                null,
                "v1",
                "v2",
                42,
                42L,
                NOW,
                "MATCHED",
                NOW,
                NOW,
                NOW);
    }
}
