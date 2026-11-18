package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import com.docqa.agentgateway.dto.PythonAskRequest;
import com.docqa.agentgateway.dto.PythonAskResponse;
import com.docqa.agentgateway.repository.AgentExecutionRepository;
import com.docqa.agentgateway.repository.ConversationRepository;
import com.docqa.agentgateway.repository.MessageRepository;
import com.docqa.common.metrics.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.common.trace.TraceIdContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AskServiceStreamTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String KEY = "ask-stream-key-0001";
    private static final String QUESTION = "请解释梯度下降";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

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
        TraceIdContext.set(TRACE_ID);
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
        sseKeepAliveScheduler.shutdown();
    }

    @Test
    void streamsFreshAnswerWithMetaDeltaDoneAndPersistsBeforeCompleting() throws Exception {
        stubFreshStream();
        when(pythonAgentClient.openStream(any(), anyString())).thenReturn(Flux.just(
                event("delta", "{\"text\":\"片段\"}"),
                event("done", "{\"anything\":true}")));
        when(pythonAgentClient.parseStreamPayload(anyString())).thenReturn(response());
        when(budgetStore.snapshot(anyString())).thenReturn(Optional.of(budget()));
        when(responseValidator.validate(any(), anyString(), any(), any(), anyList(), any()))
                .thenReturn(new ValidatedAnswer(List.of(), List.of(), "OK"));

        SseEmitter emitter = askStream();
        CapturingHandler handler = attach(emitter);

        assertThat(StringUtils.countOccurrencesOf(handler.joinedSends(), "event:")).isEqualTo(3);
        assertThat(handler.completed).isTrue();
        assertThat(handler.joinedSends())
                .contains("event:meta")
                .contains("event:delta")
                .contains("event:done")
                .contains("\"cacheHit\":false")
                .contains(TRACE_ID)
                .contains("\"tokenUsed\":120");
        InOrder order = inOrder(messageRepository, idempotencyStore);
        order.verify(messageRepository).insert(
                any(), any(), eq(CONVERSATION_ID), eq("ASSISTANT"), anyString(), anyInt(),
                any(), any(), any(), any());
        order.verify(idempotencyStore).complete(
                eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(), any(), any());
        verify(conversationLock).unlock(eq(CONVERSATION_ID), anyString());
        verify(quotaGuard).recordTokens(eq(USER_ID), eq(120L));
        verify(agentStateStore).clear(anyString());
        ArgumentCaptor<PythonAskRequest> captor = ArgumentCaptor.forClass(PythonAskRequest.class);
        verify(pythonAgentClient).openStream(captor.capture(), anyString());
        assertThat(captor.getValue().protocolVersion()).isEqualTo("1.1");
    }

    @Test
    void replayEmitsImmediateStreamWithoutCallingPython() throws Exception {
        AskData original = new com.docqa.agentgateway.dto.AskData(
                CONVERSATION_ID, MESSAGE_ID, "答案", List.of(), 0.9, "OK",
                120, 1, 2, false, false, NOW);
        when(idempotencyStore.find(USER_ID, CONVERSATION_ID, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        IdempotencyStatus.COMPLETED,
                        fingerprint(QUESTION, List.of(DOCUMENT_ID)),
                        original,
                        null,
                        NOW)));

        SseEmitter emitter = askStream();
        CapturingHandler handler = attach(emitter);

        assertThat(StringUtils.countOccurrencesOf(handler.joinedSends(), "event:")).isEqualTo(2);
        assertThat(handler.completed).isTrue();
        assertThat(handler.joinedSends())
                .contains("event:meta")
                .contains("event:done")
                .contains("\"idempotentReplay\":true");
        verify(pythonAgentClient, never()).openStream(any(), anyString());
        verify(idempotencyStore, never()).complete(any(), any(), any(), anyString(), any(), any());
        verify(conversationLock, never()).unlock(any(), anyString());
    }

    @Test
    void cacheHitEmitsImmediateStreamAndPersistsCachedAnswer() throws Exception {
        when(qaCacheService.find(eq(USER_ID), anyList(), anyList(), eq(QUESTION)))
                .thenReturn(Optional.of(new CachedAnswer("旧答案", List.of(), 0.9)));
        when(conversationLock.tryLock(eq(CONVERSATION_ID), anyString())).thenReturn(true);
        when(idempotencyStore.tryStart(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(), any()))
                .thenReturn(true);

        SseEmitter emitter = askStream();
        CapturingHandler handler = attach(emitter);

        assertThat(StringUtils.countOccurrencesOf(handler.joinedSends(), "event:")).isEqualTo(2);
        assertThat(handler.completed).isTrue();
        assertThat(handler.joinedSends())
                .contains("event:meta")
                .contains("event:done")
                .contains("\"cacheHit\":true");
        verify(pythonAgentClient, never()).openStream(any(), anyString());
        ArgumentCaptor<AgentExecution> execution = ArgumentCaptor.forClass(AgentExecution.class);
        verify(agentExecutionRepository).insert(any(), execution.capture());
        assertThat(execution.getValue().status()).isEqualTo("SUCCESS");
        assertThat(execution.getValue().cacheHit()).isTrue();
        verify(conversationLock).unlock(eq(CONVERSATION_ID), anyString());
    }

    @Test
    void openStreamFailureBeforeMetaThrowsJsonSemanticsAndReleasesLock() {
        stubFreshStream();
        when(pythonAgentClient.openStream(any(), anyString()))
                .thenThrow(new PythonCallException(ErrorCode.AGT_UPSTREAM_001, "unavailable", List.of(), false));

        assertThatThrownBy(this::askStream)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_001);

        ArgumentCaptor<AgentExecution> execution = ArgumentCaptor.forClass(AgentExecution.class);
        verify(agentExecutionRepository).insert(any(), execution.capture());
        assertThat(execution.getValue().status()).isEqualTo("FAILED");
        assertThat(execution.getValue().errorCode()).isEqualTo("AGT-UPSTREAM-001");
        verify(idempotencyStore).fail(
                eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(),
                eq("AGT-UPSTREAM-001"), any());
        verify(conversationLock).unlock(eq(CONVERSATION_ID), anyString());
        verify(agentStateStore).clear(anyString());
    }

    @Test
    void pythonErrorEventTerminatesStreamWithFailureSnapshot() throws Exception {
        stubFreshStream();
        when(pythonAgentClient.openStream(any(), anyString())).thenReturn(Flux.just(
                event("error", "{\"code\":\"AGT-UPSTREAM-001\",\"detail\":\"boom\"}")));

        SseEmitter emitter = askStream();
        CapturingHandler handler = attach(emitter);

        assertThat(StringUtils.countOccurrencesOf(handler.joinedSends(), "event:")).isEqualTo(2);
        assertThat(handler.completed).isTrue();
        assertThat(handler.joinedSends())
                .contains("event:error")
                .contains("\"code\":\"AGT-UPSTREAM-001\"")
                .contains(TRACE_ID);
        ArgumentCaptor<AgentExecution> execution = ArgumentCaptor.forClass(AgentExecution.class);
        verify(agentExecutionRepository).insert(any(), execution.capture());
        assertThat(execution.getValue().status()).isEqualTo("FAILED");
        assertThat(execution.getValue().errorCode()).isEqualTo("AGT-UPSTREAM-001");
        verify(idempotencyStore).fail(
                eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(),
                eq("AGT-UPSTREAM-001"), any());
        verify(conversationLock).unlock(eq(CONVERSATION_ID), anyString());
        verify(idempotencyStore, never()).complete(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void streamEndingWithoutDoneIsAProtocolFailure() throws Exception {
        stubFreshStream();
        when(pythonAgentClient.openStream(any(), anyString()))
                .thenReturn(Flux.empty());

        SseEmitter emitter = askStream();
        CapturingHandler handler = attach(emitter);

        assertThat(handler.completed).isTrue();
        assertThat(handler.joinedSends())
                .contains("event:error")
                .contains("AGT-UPSTREAM-004");
        ArgumentCaptor<AgentExecution> execution = ArgumentCaptor.forClass(AgentExecution.class);
        verify(agentExecutionRepository).insert(any(), execution.capture());
        assertThat(execution.getValue().status()).isEqualTo("FAILED");
        assertThat(execution.getValue().errorCode()).isEqualTo("AGT-UPSTREAM-004");
        verify(conversationLock).unlock(eq(CONVERSATION_ID), anyString());
    }

    @Test
    void clientDisconnectDisposesUpstreamAndMarksFailure() throws Exception {
        stubFreshStream();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(pythonAgentClient.openStream(any(), anyString()))
                .thenReturn(Flux.<ServerSentEvent<String>>never().doOnCancel(() -> cancelled.set(true)));

        SseEmitter emitter = askStream();
        CapturingHandler handler = attach(emitter);
        assertThat(StringUtils.countOccurrencesOf(handler.joinedSends(), "event:")).isEqualTo(1);
        assertThat(handler.joinedSends()).contains("event:meta");

        handler.runCompletion();
        emitter.complete();

        assertThat(cancelled).isTrue();
        ArgumentCaptor<AgentExecution> execution = ArgumentCaptor.forClass(AgentExecution.class);
        verify(agentExecutionRepository).insert(any(), execution.capture());
        assertThat(execution.getValue().status()).isEqualTo("FAILED");
        assertThat(execution.getValue().errorCode()).isEqualTo("AGT-UPSTREAM-002");
        verify(idempotencyStore).fail(
                eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(),
                eq("AGT-UPSTREAM-002"), any());
        verify(conversationLock).unlock(eq(CONVERSATION_ID), anyString());
        verify(agentStateStore).clear(anyString());
    }

    private SseEmitter askStream() {
        return service.askStream(USER_ID, CONVERSATION_ID, KEY,
                new AskRequest(QUESTION, List.of(DOCUMENT_ID)));
    }

    private void stubFreshStream() {
        when(conversationLock.tryLock(eq(CONVERSATION_ID), anyString())).thenReturn(true);
        when(idempotencyStore.tryStart(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), anyString(), any()))
                .thenReturn(true);
    }

    private ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }

    private PythonAskResponse response() {
        return new PythonAskResponse(
                "1.1", TRACE_ID, "答案", List.of(), 0.9, "OK", 120, 1, 2, List.of(), NOW);
    }

    private BudgetSnapshot budget() {
        return new BudgetSnapshot(120, 1, 2, 8000, 3, 3, NOW.plusSeconds(15));
    }

    private String fingerprint(String question, List<UUID> documentIds) {
        String scope = documentIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        return Hashing.sha256(USER_ID + "|" + CONVERSATION_ID + "|" + question + "|" + scope);
    }

    /**
     * 捕获 SseEmitter 下发的帧与完成信号；手动触发 completion 回调以模拟客户端断开。
     * ResponseBodyEmitter.Handler 是包私有接口，测试侧无法直接实现，
     * 这里用动态代理包一层，行为与容器侧 HttpMessageConvertingHandler 对齐。
     */
    private static final class CapturingHandler implements InvocationHandler {
        private final List<String> sends = new ArrayList<>();
        private boolean completed;
        private Runnable completionCallback;
        private Runnable timeoutCallback;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "send" -> captureSends(args[0]);
                case "complete" -> completed = true;
                case "onCompletion" -> completionCallback = (Runnable) args[0];
                case "onTimeout" -> timeoutCallback = (Runnable) args[0];
                default -> {
                }
            }
            return null;
        }

        private void captureSends(Object dataWithMediaTypes) {
            StringBuilder frame = new StringBuilder();
            for (Object item : (Iterable<?>) dataWithMediaTypes) {
                try {
                    Object data = item.getClass().getMethod("getData").invoke(item);
                    frame.append(data);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("无法解包 SSE 帧", ex);
                }
            }
            sends.add(frame.toString());
        }

        private String joinedSends() {
            return String.join("", sends);
        }

        private void runCompletion() {
            if (completionCallback != null) {
                completionCallback.run();
            }
        }
    }

    private CapturingHandler attach(SseEmitter emitter) throws Exception {
        Class<?> handlerClass = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        CapturingHandler handler = new CapturingHandler();
        Object proxy = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[]{handlerClass}, handler);
        Method initialize = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handlerClass);
        initialize.setAccessible(true);
        initialize.invoke(emitter, proxy);
        return handler;
    }
}
