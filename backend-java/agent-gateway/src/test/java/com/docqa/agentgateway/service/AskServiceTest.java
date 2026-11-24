package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.config.AgentProperties;
import com.docqa.agentgateway.config.PythonProperties;
import com.docqa.agentgateway.domain.AgentExecution;
import com.docqa.agentgateway.dto.AskData;
import com.docqa.agentgateway.dto.AskRequest;
import com.docqa.agentgateway.dto.AuthorizedDocument;
import com.docqa.agentgateway.dto.Citation;
import com.docqa.agentgateway.dto.PythonAskRequest;
import com.docqa.agentgateway.dto.PythonAskResponse;
import com.docqa.agentgateway.dto.PythonHistoryMessage;
import com.docqa.agentgateway.dto.StoredMessage;
import com.docqa.agentgateway.repository.AgentExecutionRepository;
import com.docqa.agentgateway.repository.ConversationRepository;
import com.docqa.agentgateway.repository.MessageRepository;
import com.docqa.common.metrics.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.common.trace.TraceIdContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AskServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String KEY = "ask-key-000001";
    private static final String QUESTION = "请解释梯度下降";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Citation CITATION = new Citation(DOCUMENT_ID, 3, 0.9, "片段");

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationService conversationService;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private AgentExecutionRepository agentExecutionRepository;
    @Mock
    private DocumentScopeService documentScopeService;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private QuotaGuard quotaGuard;
    @Mock
    private ConversationLock conversationLock;
    @Mock
    private IdempotencyStore idempotencyStore;
    @Mock
    private QaCacheService qaCacheService;
    @Mock
    private BudgetStore budgetStore;
    @Mock
    private AgentStateStore agentStateStore;
    @Mock
    private KillSwitch killSwitch;
    @Mock
    private PiiMasker piiMasker;
    @Mock
    private PythonAgentClient pythonAgentClient;
    @Mock
    private PythonResponseValidator responseValidator;
    @Mock
    private AuditEventValidator auditEventValidator;
    @Mock
    private TransactionRunner transactionRunner;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentProperties agentProperties = new AgentProperties();
    private final PythonProperties pythonProperties = new PythonProperties();
    private AuditEventFactory auditEventFactory;
    private ThreadPoolTaskScheduler sseKeepAliveScheduler;
    private AskService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        auditEventFactory = new AuditEventFactory(Clock.fixed(NOW, ZoneOffset.UTC));
        sseKeepAliveScheduler = new ThreadPoolTaskScheduler();
        sseKeepAliveScheduler.setPoolSize(1);
        sseKeepAliveScheduler.setDaemon(true);
        sseKeepAliveScheduler.initialize();
        service = new AskService(
                Clock.fixed(NOW, ZoneOffset.UTC),
                conversationRepository,
                conversationService,
                messageRepository,
                agentExecutionRepository,
                documentScopeService,
                rateLimiter,
                quotaGuard,
                conversationLock,
                idempotencyStore,
                qaCacheService,
                budgetStore,
                agentStateStore,
                killSwitch,
                piiMasker,
                pythonAgentClient,
                responseValidator,
                auditEventFactory,
                auditEventValidator,
                transactionRunner,
                objectMapper,
                agentProperties,
                pythonProperties,
                sseKeepAliveScheduler,
                new BusinessMetrics(new SimpleMeterRegistry()));

        lenient().when(piiMasker.mask(anyString()))
                .thenAnswer(invocation -> new PiiMaskingResult(invocation.getArgument(0), List.of(), 0));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(transactionRunner).execute(any(Runnable.class));
        lenient().when(auditEventValidator.validateError(any(), anyString(), any(), any()))
                .thenReturn(new AuditValidation(List.of(), false));
        lenient().when(messageRepository.findRecent(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(killSwitch.isActive()).thenReturn(false);
        lenient().when(documentScopeService.resolve(eq(USER_ID), anyList()))
                .thenReturn(List.of(new AuthorizedDocument(DOCUMENT_ID, "v1")));
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
        TenantContext.clear();
    }

    @Test
    void executesFreshAnswerAndPersistsEverything() {
        stubHappyPath();

        AskData result = service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        assertThat(result.answer()).isEqualTo("答案");
        assertThat(result.budgetStatus()).isEqualTo("OK");
        assertThat(result.cacheHit()).isFalse();
        assertThat(result.idempotentReplay()).isFalse();
        verify(messageRepository, org.mockito.Mockito.times(2)).insert(
                any(), any(), eq(CONVERSATION_ID), anyString(), anyString(), anyInt(),
                any(), any(), any(), any());
        verify(agentExecutionRepository).insert(any(), any(AgentExecution.class));
        verify(idempotencyStore).complete(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(), any(AskData.class), any());
        verify(qaCacheService).put(eq(USER_ID), anyList(), anyList(), eq(QUESTION), any(CachedAnswer.class));
    }

    @Test
    void autoCreatesConversationOnlyAfterDocumentScopeResolves() {
        stubHappyPath();

        service.ask(USER_ID, CONVERSATION_ID, KEY, new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        InOrder order = inOrder(documentScopeService, conversationService);
        order.verify(documentScopeService).resolve(USER_ID, List.of(DOCUMENT_ID));
        order.verify(conversationService)
                .ensureOwned(CONVERSATION_ID, USER_ID, List.of(DOCUMENT_ID));
    }

    @Test
    void buildsGovernedPythonRequest() {
        stubHappyPath();

        service.ask(USER_ID, CONVERSATION_ID, KEY, new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        ArgumentCaptor<PythonAskRequest> captor = ArgumentCaptor.forClass(PythonAskRequest.class);
        verify(pythonAgentClient).ask(captor.capture(), anyString());
        PythonAskRequest request = captor.getValue();
        assertThat(request.protocolVersion()).isEqualTo("1.0");
        assertThat(request.tenantId()).isEqualTo(TENANT_ID);
        assertThat(request.question()).isEqualTo(QUESTION);
        assertThat(request.piiMaskedQuestion()).isEqualTo(QUESTION);
        assertThat(request.piiMaskingVersion()).isEqualTo("pii-v2");
        assertThat(request.tokenBudget()).isEqualTo(8000);
        assertThat(request.maxToolCalls()).isEqualTo(3);
        assertThat(request.maxRounds()).isEqualTo(3);
        assertThat(request.allowedTools())
                .containsExactly(
                        "document_search",
                        "calculator",
                        "current_time",
                        "web_fetch",
                        "web_search",
                        "academic_search",
                        "wikipedia_lookup");
        assertThat(request.deadlineMs()).isBetween(100, 15000);
        assertThat(request.documentScopes())
                .containsExactly(new com.docqa.agentgateway.dto.PythonDocumentScope(DOCUMENT_ID, "v1"));
    }

    @Test
    void neverSendsOriginalPiiToPython() {
        when(piiMasker.mask(QUESTION))
                .thenReturn(new PiiMaskingResult("[PHONE]", List.of("PHONE"), 1));
        stubHappyPath();

        service.ask(USER_ID, CONVERSATION_ID, KEY, new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        ArgumentCaptor<PythonAskRequest> captor = ArgumentCaptor.forClass(PythonAskRequest.class);
        verify(pythonAgentClient).ask(captor.capture(), anyString());
        assertThat(captor.getValue().piiMaskedQuestion()).isEqualTo("[PHONE]");
    }

    @Test
    void masksHistoryBeforeSendingToPython() {
        when(messageRepository.findRecent(TENANT_ID, CONVERSATION_ID, agentProperties.getHistoryLimit()))
                .thenReturn(List.of(
                        new StoredMessage(UUID.randomUUID(), "USER", "邮箱 a@b.com", NOW),
                        new StoredMessage(UUID.randomUUID(), "ASSISTANT", "旧回答", NOW)));
        when(piiMasker.mask("邮箱 a@b.com"))
                .thenReturn(new PiiMaskingResult("邮箱 [EMAIL]", List.of("EMAIL"), 1));
        stubHappyPath();

        service.ask(USER_ID, CONVERSATION_ID, KEY, new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        ArgumentCaptor<PythonAskRequest> captor = ArgumentCaptor.forClass(PythonAskRequest.class);
        verify(pythonAgentClient).ask(captor.capture(), anyString());
        assertThat(captor.getValue().history()).containsExactly(
                new PythonHistoryMessage("USER", "邮箱 [EMAIL]"),
                new PythonHistoryMessage("ASSISTANT", "旧回答"));
    }

    @Test
    void replaysCompletedIdempotentRequestWithoutLocking() {
        AskData original = new AskData(
                CONVERSATION_ID, MESSAGE_ID, "答案", List.of(), 0.9, "OK",
                120, 1, 2, false, false, NOW, null);
        when(idempotencyStore.find(USER_ID, CONVERSATION_ID, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        IdempotencyStatus.COMPLETED,
                        fingerprint(QUESTION, List.of(DOCUMENT_ID)),
                        original,
                        null,
                        NOW)));

        AskData result = service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        assertThat(result.idempotentReplay()).isTrue();
        assertThat(result.messageId()).isEqualTo(MESSAGE_ID);
        verify(conversationLock, never()).tryLock(any(), anyString());
        verify(pythonAgentClient, never()).ask(any(), anyString());
    }

    @Test
    void rejectsSameKeyWithDifferentRequest() {
        AskData original = new AskData(
                CONVERSATION_ID, MESSAGE_ID, "答案", List.of(), 0.9, "OK",
                120, 1, 2, false, false, NOW, null);
        when(idempotencyStore.find(USER_ID, CONVERSATION_ID, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        IdempotencyStatus.COMPLETED, "different-fingerprint", original, null, NOW)));

        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_CONFLICT_001);
    }

    @Test
    void rejectsBusyConversation() {
        when(conversationLock.tryLock(eq(CONVERSATION_ID), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_CONFLICT_001);
        verify(documentScopeService, never()).resolve(any(), any());
    }

    @Test
    void rejectsForeignConversationBeforeLocking() {
        doThrow(new BusinessException(ErrorCode.AGT_NOT_FOUND_001))
                .when(conversationService).requireAccessible(CONVERSATION_ID, USER_ID);

        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_NOT_FOUND_001);
        verify(conversationLock, never()).tryLock(any(), anyString());
    }

    @Test
    void rejectsInvalidIdempotencyKeyBeforeRateLimit() {
        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, "short",
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_VALIDATION_001);
        verify(rateLimiter, never()).check(any());
    }

    @Test
    void returnsCachedAnswerWithoutCallingPython() {
        when(qaCacheService.find(eq(USER_ID), anyList(), anyList(), eq(QUESTION)))
                .thenReturn(Optional.of(new CachedAnswer("旧答案", List.of(CITATION), 0.9)));
        when(conversationLock.tryLock(eq(CONVERSATION_ID), anyString())).thenReturn(true);
        when(idempotencyStore.tryStart(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(), any()))
                .thenReturn(true);

        AskData result = service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        assertThat(result.answer()).isEqualTo("旧答案");
        assertThat(result.cacheHit()).isTrue();
        assertThat(result.tokenUsed()).isZero();
        verify(pythonAgentClient, never()).ask(any(), anyString());
        verify(messageRepository, org.mockito.Mockito.times(2)).insert(
                any(), any(), eq(CONVERSATION_ID), anyString(), anyString(), anyInt(),
                any(), any(), any(), any());
        verify(agentExecutionRepository).insert(any(), any(AgentExecution.class));
    }

    @Test
    void killSwitchBlocksBeforePythonAndPersistsFailure() {
        when(conversationLock.tryLock(eq(CONVERSATION_ID), anyString())).thenReturn(true);
        when(killSwitch.isActive()).thenReturn(true);

        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_003);

        verify(pythonAgentClient, never()).ask(any(), anyString());
        ArgumentCaptor<AgentExecution> execution = ArgumentCaptor.forClass(AgentExecution.class);
        verify(agentExecutionRepository).insert(any(), execution.capture());
        assertThat(execution.getValue().status()).isEqualTo("FAILED");
        assertThat(execution.getValue().errorCode()).isEqualTo("AGT-UPSTREAM-003");
    }

    @Test
    void mapsPythonTimeoutTo504AndWritesFailureSnapshot() {
        stubUntilPython();
        when(pythonAgentClient.ask(any(), anyString()))
                .thenThrow(new PythonCallException(ErrorCode.AGT_UPSTREAM_002, "timeout", List.of(), false));

        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_002);

        ArgumentCaptor<AgentExecution> execution = ArgumentCaptor.forClass(AgentExecution.class);
        verify(agentExecutionRepository).insert(any(), execution.capture());
        assertThat(execution.getValue().status()).isEqualTo("FAILED");
        assertThat(execution.getValue().errorCode()).isEqualTo("AGT-UPSTREAM-002");
        verify(agentStateStore).clear(anyString());
    }

    @Test
    void mapsProtocolViolationTo502() {
        stubUntilPython();
        when(pythonAgentClient.ask(any(), anyString())).thenReturn(response());
        when(budgetStore.snapshot(anyString())).thenReturn(Optional.of(budget()));
        when(responseValidator.validate(any(), anyString(), any(), any(), anyList(), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_004, "bad protocol"));

        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_004);
        verify(qaCacheService, never()).put(any(), anyList(), anyList(), anyString(), any());
    }

    @Test
    void lowConfidenceAnswerIsNotCached() {
        stubUntilPython();
        when(pythonAgentClient.ask(any(), anyString())).thenReturn(response());
        when(budgetStore.snapshot(anyString())).thenReturn(Optional.of(budget()));
        when(responseValidator.validate(any(), anyString(), any(), any(), anyList(), any()))
                .thenReturn(new ValidatedAnswer(List.of(), List.of(), "LOW_CONFIDENCE"));

        AskData result = service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        assertThat(result.budgetStatus()).isEqualTo("LOW_CONFIDENCE");
        verify(qaCacheService, never()).put(any(), anyList(), anyList(), anyString(), any());
    }

    @Test
    void mapsPersistenceFailureToInternalErrorWithoutCaching() {
        stubHappyPath();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("audit outbox rejected"))
                .when(agentExecutionRepository).insert(any(), any(AgentExecution.class));

        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_INTERNAL_001);

        verify(qaCacheService, never()).put(any(), anyList(), anyList(), anyString(), any());
        verify(idempotencyStore).fail(
                eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(),
                eq(ErrorCode.AGT_INTERNAL_001.code()), any());
    }

    @Test
    void mapsDuplicateUserMessageToConflictWhenIdempotencyRecordIsLost() {
        stubUntilPython();
        doThrow(new DuplicateKeyException("uk_message_client_request"))
                .when(messageRepository).insert(
                        any(), any(), any(), anyString(), anyString(), anyInt(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_CONFLICT_001);

        verify(pythonAgentClient, never()).ask(any(), anyString());
        verify(idempotencyStore).fail(
                eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(),
                eq(ErrorCode.AGT_CONFLICT_001.code()), any());
    }

    @Test
    void initializesBudgetBeforeCallingPython() {
        stubHappyPath();

        service.ask(USER_ID, CONVERSATION_ID, KEY, new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        InOrder order = inOrder(budgetStore, pythonAgentClient);
        order.verify(budgetStore).initialize(anyString(), eq(8000), eq(3), eq(3), any(Instant.class));
        order.verify(pythonAgentClient).ask(any(), anyString());
    }

    @Test
    void reservesQuotaAfterKillSwitchAndBeforeTheCacheLookup() {
        stubHappyPath();

        service.ask(USER_ID, CONVERSATION_ID, KEY, new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        InOrder order = inOrder(killSwitch, quotaGuard, qaCacheService);
        order.verify(killSwitch).isActive();
        order.verify(quotaGuard).reserve(USER_ID);
        order.verify(qaCacheService).find(eq(USER_ID), anyList(), anyList(), eq(QUESTION));
    }

    @Test
    void replaysIdempotentRequestsWithoutConsumingQuota() {
        AskData original = new AskData(
                CONVERSATION_ID, MESSAGE_ID, "答案", List.of(), 0.9, "OK",
                120, 1, 2, false, false, NOW, null);
        when(idempotencyStore.find(USER_ID, CONVERSATION_ID, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        IdempotencyStatus.COMPLETED,
                        fingerprint(QUESTION, List.of(DOCUMENT_ID)),
                        original,
                        null,
                        NOW)));

        service.ask(USER_ID, CONVERSATION_ID, KEY, new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        verify(quotaGuard, never()).reserve(any());
    }

    @Test
    void killSwitchRejectionsDoNotConsumeQuota() {
        when(conversationLock.tryLock(eq(CONVERSATION_ID), anyString())).thenReturn(true);
        when(killSwitch.isActive()).thenReturn(true);

        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(BusinessException.class);

        verify(quotaGuard, never()).reserve(any());
    }

    @Test
    void cacheHitsConsumeOneQuestionButNoTokens() {
        when(qaCacheService.find(eq(USER_ID), anyList(), anyList(), eq(QUESTION)))
                .thenReturn(Optional.of(new CachedAnswer("旧答案", List.of(CITATION), 0.9)));
        when(conversationLock.tryLock(eq(CONVERSATION_ID), anyString())).thenReturn(true);
        when(idempotencyStore.tryStart(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(), any()))
                .thenReturn(true);

        service.ask(USER_ID, CONVERSATION_ID, KEY, new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        verify(quotaGuard).reserve(USER_ID);
        verify(quotaGuard, never()).recordTokens(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void freshAnswersRecordTheActualTokenUsage() {
        stubHappyPath();

        service.ask(USER_ID, CONVERSATION_ID, KEY, new AskRequest(QUESTION, List.of(DOCUMENT_ID), null));

        verify(quotaGuard).recordTokens(USER_ID, 120L);
    }

    @Test
    void quotaRejectionsStopTheRequestBeforeTheCacheAndPython() {
        when(conversationLock.tryLock(eq(CONVERSATION_ID), anyString())).thenReturn(true);
        doThrow(new QuotaExceededException(3600)).when(quotaGuard).reserve(USER_ID);

        assertThatThrownBy(() -> service.ask(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID), null)))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(ex -> assertThat(((QuotaExceededException) ex).getRetryAfterSeconds())
                        .isEqualTo(3600));

        verify(qaCacheService, never()).find(any(), anyList(), anyList(), anyString());
        verify(pythonAgentClient, never()).ask(any(), anyString());
    }

    private void stubHappyPath() {
        stubUntilPython();
        when(pythonAgentClient.ask(any(), anyString())).thenReturn(response());
        when(budgetStore.snapshot(anyString())).thenReturn(Optional.of(budget()));
        when(responseValidator.validate(any(), anyString(), any(), any(), anyList(), any()))
                .thenReturn(new ValidatedAnswer(List.of(CITATION), List.of(), "OK"));
    }

    private void stubUntilPython() {
        when(conversationLock.tryLock(eq(CONVERSATION_ID), anyString())).thenReturn(true);
        when(idempotencyStore.tryStart(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(), any()))
                .thenReturn(true);
    }

    private PythonAskResponse response() {
        return new PythonAskResponse(
                "1.0", TRACE_ID, "答案", List.of(), 0.9, "OK", 120, 1, 2, List.of(), NOW, null);
    }

    private BudgetSnapshot budget() {
        return new BudgetSnapshot(120, 1, 2, 8000, 3, 3, NOW.plusSeconds(15));
    }

    private String fingerprint(String question, List<UUID> documentIds) {
        String scope = documentIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        return Hashing.sha256(USER_ID + "|" + CONVERSATION_ID + "|" + question + "|" + scope);
    }
}
