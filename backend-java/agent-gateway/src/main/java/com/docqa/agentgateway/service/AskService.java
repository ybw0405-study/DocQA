package com.docqa.agentgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.config.AgentProperties;
import com.docqa.agentgateway.config.PythonProperties;
import com.docqa.agentgateway.domain.AgentExecution;
import com.docqa.agentgateway.dto.AskData;
import com.docqa.agentgateway.dto.AskRequest;
import com.docqa.agentgateway.dto.AskStreamDonePayload;
import com.docqa.agentgateway.dto.AskStreamMetaPayload;
import com.docqa.agentgateway.dto.AuditEvent;
import com.docqa.agentgateway.dto.AuthorizedDocument;
import com.docqa.agentgateway.dto.PythonAskRequest;
import com.docqa.agentgateway.dto.PythonAskResponse;
import com.docqa.agentgateway.dto.PythonDocumentScope;
import com.docqa.agentgateway.dto.PythonHistoryMessage;
import com.docqa.agentgateway.dto.StoredMessage;
import com.docqa.agentgateway.repository.AgentExecutionRepository;
import com.docqa.agentgateway.repository.ConversationRepository;
import com.docqa.agentgateway.repository.MessageRepository;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.error.ProblemDetailFactory;
import com.docqa.common.metrics.BusinessMetrics;
import com.docqa.common.tenant.TenantContext;
import com.docqa.common.trace.TraceIdContext;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

@Service
public class AskService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AskService.class);
    private static final int MIN_DEADLINE_MS = 100;
    private static final int MAX_DEADLINE_MS = 15000;
    private static final int MAX_HISTORY_MESSAGE_CHARS = 4000;
    private static final long STREAM_TIMEOUT_BUFFER_MS = 10_000;
    private static final Duration KEEP_ALIVE_INTERVAL = Duration.ofSeconds(5);
    private static final String PYTHON_PROTOCOL_SYNC = "1.0";
    private static final String PYTHON_PROTOCOL_STREAM = "1.1";

    private final Clock clock;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final MessageRepository messageRepository;
    private final AgentExecutionRepository agentExecutionRepository;
    private final DocumentScopeService documentScopeService;
    private final RateLimiter rateLimiter;
    private final QuotaGuard quotaGuard;
    private final ConversationLock conversationLock;
    private final IdempotencyStore idempotencyStore;
    private final QaCacheService qaCacheService;
    private final BudgetStore budgetStore;
    private final AgentStateStore agentStateStore;
    private final KillSwitch killSwitch;
    private final PiiMasker piiMasker;
    private final PythonAgentClient pythonAgentClient;
    private final PythonResponseValidator responseValidator;
    private final AuditEventFactory auditEventFactory;
    private final AuditEventValidator auditEventValidator;
    private final TransactionRunner transactionRunner;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final PythonProperties pythonProperties;
    private final ThreadPoolTaskScheduler sseKeepAliveScheduler;
    private final BusinessMetrics metrics;

    public AskService(
            Clock clock,
            ConversationRepository conversationRepository,
            ConversationService conversationService,
            MessageRepository messageRepository,
            AgentExecutionRepository agentExecutionRepository,
            DocumentScopeService documentScopeService,
            RateLimiter rateLimiter,
            QuotaGuard quotaGuard,
            ConversationLock conversationLock,
            IdempotencyStore idempotencyStore,
            QaCacheService qaCacheService,
            BudgetStore budgetStore,
            AgentStateStore agentStateStore,
            KillSwitch killSwitch,
            PiiMasker piiMasker,
            PythonAgentClient pythonAgentClient,
            PythonResponseValidator responseValidator,
            AuditEventFactory auditEventFactory,
            AuditEventValidator auditEventValidator,
            TransactionRunner transactionRunner,
            ObjectMapper objectMapper,
            AgentProperties agentProperties,
            PythonProperties pythonProperties,
            ThreadPoolTaskScheduler sseKeepAliveScheduler,
            BusinessMetrics metrics) {
        this.clock = clock;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.agentExecutionRepository = agentExecutionRepository;
        this.documentScopeService = documentScopeService;
        this.rateLimiter = rateLimiter;
        this.quotaGuard = quotaGuard;
        this.conversationLock = conversationLock;
        this.idempotencyStore = idempotencyStore;
        this.qaCacheService = qaCacheService;
        this.budgetStore = budgetStore;
        this.agentStateStore = agentStateStore;
        this.killSwitch = killSwitch;
        this.piiMasker = piiMasker;
        this.pythonAgentClient = pythonAgentClient;
        this.responseValidator = responseValidator;
        this.auditEventFactory = auditEventFactory;
        this.auditEventValidator = auditEventValidator;
        this.transactionRunner = transactionRunner;
        this.objectMapper = objectMapper;
        this.agentProperties = agentProperties;
        this.pythonProperties = pythonProperties;
        this.sseKeepAliveScheduler = sseKeepAliveScheduler;
        this.metrics = metrics;
    }

    public AskData ask(UUID userId, UUID conversationId, String idempotencyKey, AskRequest request) {
        // 计时起点取方法入口而不是 prepare 之后：限流拒绝、校验失败、幂等冲突同样是「问答请求的
        // 一种终态」，漏掉它们会让 §4.3 的失败率分母偏小。prepare 自行抛异常时 prepared 为 null，
        // 锁已由 prepare 内部释放，这里不能重复 unlock。
        Instant requestStartedAt = clock.instant();
        PreparedAsk prepared = null;
        AskData result = null;
        try {
            prepared = prepare(userId, conversationId, IdempotencyKeys.validate(idempotencyKey), request, false);
            if (prepared.replay().isPresent()) {
                result = prepared.replay().get();
            } else if (prepared.cached().isPresent()) {
                result = executeCached(prepared);
            } else {
                result = executeFresh(prepared);
            }
            return result;
        } finally {
            if (prepared != null && prepared.lockHeld()) {
                conversationLock.unlock(prepared.conversationId(), prepared.traceId());
            }
            recordQaOutcome(requestStartedAt, result);
        }
    }

    /**
     * 流式问答（SSE 四帧协议 meta → delta… → done | error）。
     * 管线前段与同步 ask 完全同源：首个事件帧之前的一切拒绝都抛出 {@link BusinessException}，
     * 由异常处理器输出普通 JSON ProblemDetail；meta 帧下发之后的失败一律以 error 帧终止。
     */
    public SseEmitter askStream(UUID userId, UUID conversationId, String idempotencyKey, AskRequest request) {
        // 流式的三种收尾点各自记账，互不重叠：立即完成短流在 completeImmediateStream、
        // 正常流在 StreamSession.cleanup、首帧之前抛出的在这里。
        Instant requestStartedAt = clock.instant();
        PreparedAsk prepared = null;
        try {
            prepared = prepare(userId, conversationId, IdempotencyKeys.validate(idempotencyKey), request, true);
            return startStream(prepared);
        } catch (RuntimeException ex) {
            // 首个事件帧之前的异常路径：锁尚未移交 StreamSession，在此释放
            if (prepared != null && prepared.lockHeld()) {
                conversationLock.unlock(prepared.conversationId(), prepared.traceId());
            }
            recordQaOutcome(requestStartedAt, null);
            throw ex;
        }
    }

    private SseEmitter startStream(PreparedAsk prepared) {
        SseEmitter emitter = new SseEmitter(pythonProperties.getDeadline().toMillis() + STREAM_TIMEOUT_BUFFER_MS);
        if (prepared.replay().isPresent()) {
            return completeImmediateStream(prepared, emitter, prepared.replay().get());
        }
        if (prepared.cached().isPresent()) {
            AskData result = executeCached(prepared);
            return completeImmediateStream(prepared, emitter, result);
        }
        Optional<FreshExecution> started = tryStartFresh(prepared);
        if (started.isEmpty()) {
            AskData replayed = replayOrConflict(
                    idempotencyStore.find(prepared.userId(), prepared.conversationId(), prepared.key())
                            .orElseThrow(() -> new BusinessException(ErrorCode.AGT_CONFLICT_001)),
                    prepared.fingerprint());
            return completeImmediateStream(prepared, emitter, replayed);
        }
        FreshExecution execution = started.get();
        PythonAskRequest pythonRequest = pythonRequest(prepared, execution.deadlineMs());
        Flux<ServerSentEvent<String>> events;
        try {
            events = pythonAgentClient.openStream(pythonRequest, prepared.traceId());
        } catch (PythonCallException ex) {
            // meta 未发：维持首字节前 JSON 语义，与非流式 executeFresh 的 PythonCallException 分支一致
            failAfterUserMessage(prepared, execution.userMessageId(), ex);
            clearState(prepared.traceId());
            throw new BusinessException(ex.getErrorCode(), ex.getMessage());
        }
        UUID assistantMessageId = UUID.randomUUID();
        new StreamSession(prepared, emitter, execution.userMessageId(), assistantMessageId).begin(events);
        return emitter;
    }

    /**
     * 管线前段（校验 / 限流 / 会话锁 / 幂等查重 / 配额 / Kill Switch / PII / 历史 / 缓存探测），
     * ask 与 askStream 共用。返回的 {@link PreparedAsk} 携带锁持有状态，
     * 调用方必须在 finally 中按 lockHeld 释放；本方法内部抛出时自行释放。
     */
    private PreparedAsk prepare(UUID userId, UUID conversationId, String key, AskRequest request, boolean stream) {
        UUID tenantId = TenantContext.require();
        String question = validateQuestion(request);
        List<UUID> documentIds = validateDocumentIds(request.documentIds());
        Instant startedAt = clock.instant();
        String traceId = TraceIdContext.currentOrCreate();

        rateLimiter.check(userId);
        conversationService.requireAccessible(conversationId, userId);

        String fingerprint = fingerprint(userId, conversationId, question, documentIds);
        Optional<IdempotencyRecord> existing = idempotencyStore.find(userId, conversationId, key);
        if (existing.isPresent()) {
            return new PreparedAsk(
                    userId, tenantId, conversationId, key, fingerprint, question, documentIds,
                    List.of(), List.of(), null, traceId, startedAt, false, stream,
                    Optional.of(replayOrConflict(existing.get(), fingerprint)), Optional.empty());
        }

        if (!conversationLock.tryLock(conversationId, traceId)) {
            throw new BusinessException(ErrorCode.AGT_CONFLICT_001, "Conversation is processing another request");
        }
        try {
            existing = idempotencyStore.find(userId, conversationId, key);
            if (existing.isPresent()) {
                return new PreparedAsk(
                        userId, tenantId, conversationId, key, fingerprint, question, documentIds,
                        List.of(), List.of(), null, traceId, startedAt, true, stream,
                        Optional.of(replayOrConflict(existing.get(), fingerprint)), Optional.empty());
            }
            List<AuthorizedDocument> documents = documentScopeService.resolve(userId, documentIds);
            conversationService.ensureOwned(conversationId, userId, documentIds);
            if (killSwitch.isActive()) {
                handleKillSwitch(tenantId, userId, conversationId, traceId, startedAt);
            }
            // 准入即计数：幂等重放已在上面返回；Kill Switch 拒绝的请求不消耗配额；缓存命中仍计 1 次问答。
            quotaGuard.reserve(userId);

            PiiMaskingResult masked = piiMasker.mask(question);
            List<QaCacheService.PythonHistoryMessageMask> history = loadHistory(tenantId, conversationId);
            Optional<CachedAnswer> usable = qaCacheService
                    .find(userId, documents, history, masked.masked())
                    .filter(AskService.this::isUsable);
            // 命中口径取「过滤之后」：查到了但不可用（过期/置信度不足）的条目对使用者等同于
            // 未命中，若把这种记成 hit，缓存命中率会虚高而实际几乎没有缓存收益。
            metrics.qaCacheLookup(usable.isPresent());
            return new PreparedAsk(
                    userId, tenantId, conversationId, key, fingerprint, question, documentIds,
                    documents, history, masked, traceId, startedAt, true, stream,
                    Optional.empty(), usable);
        } catch (RuntimeException ex) {
            conversationLock.unlock(conversationId, traceId);
            throw ex;
        }
    }

    /** 幂等重放与缓存命中的短流：同步落库完成后发 meta + 单条 done 并立即关闭。 */
    private SseEmitter completeImmediateStream(PreparedAsk prepared, SseEmitter emitter, AskData data) {
        try {
            sendStreamEvent(emitter, "meta", metaJson(prepared, data));
            sendStreamEvent(emitter, "done", doneJson(data, prepared.traceId()));
            emitter.complete();
        } catch (IOException | IllegalStateException ex) {
            LOGGER.warn("SSE immediate stream aborted before completion: traceId={}", prepared.traceId());
        } finally {
            if (prepared.lockHeld()) {
                conversationLock.unlock(prepared.conversationId(), prepared.traceId());
            }
            recordQaOutcome(prepared.startedAt(), data);
        }
        return emitter;
    }

    private static void sendStreamEvent(SseEmitter emitter, String eventName, String json) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(json));
    }

    private String metaJson(PreparedAsk prepared, AskData data) {
        return toJson(new AskStreamMetaPayload(
                prepared.conversationId(), data.messageId(), prepared.traceId(), data.cacheHit()));
    }

    private String doneJson(AskData data, String traceId) {
        return toJson(AskStreamDonePayload.of(data, traceId));
    }

    private String deltaJson(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
            if (node == null || !node.hasNonNull("text") || !node.get("text").isTextual()) {
                throw new PythonCallException(
                        ErrorCode.AGT_UPSTREAM_004,
                        "Python stream delta payload is missing required fields",
                        List.of(),
                        true);
            }
            return data;
        } catch (JsonProcessingException ex) {
            throw new PythonCallException(
                    ErrorCode.AGT_UPSTREAM_004, "Python stream delta payload is not valid JSON", List.of(), true);
        }
    }

    private AskData executeFresh(PreparedAsk prepared) {
        Optional<FreshExecution> started = tryStartFresh(prepared);
        if (started.isEmpty()) {
            return replayOrConflict(
                    idempotencyStore.find(prepared.userId(), prepared.conversationId(), prepared.key())
                            .orElseThrow(() -> new BusinessException(ErrorCode.AGT_CONFLICT_001)),
                    prepared.fingerprint());
        }
        FreshExecution execution = started.get();
        try {
            PythonAskRequest pythonRequest = pythonRequest(prepared, execution.deadlineMs());
            PythonAskResponse response = pythonAgentClient.ask(pythonRequest, prepared.traceId());
            quotaGuard.recordTokens(prepared.userId(), response.tokenUsed());
            BudgetSnapshot budget = budgetStore.snapshot(prepared.traceId()).orElse(null);
            ValidatedAnswer validated = responseValidator.validate(
                    response, prepared.traceId(), prepared.userId(), prepared.conversationId(),
                    prepared.documents(), budget);
            AskData result = persistSuccess(
                    prepared, execution.userMessageId(), null, response, validated, false);
            if ("OK".equals(validated.budgetStatus())) {
                cacheResult(prepared, response, validated);
            }
            completeIdempotency(prepared, result);
            return result;
        } catch (PythonCallException ex) {
            failAfterUserMessage(prepared, execution.userMessageId(), ex);
            throw new BusinessException(ex.getErrorCode(), ex.getMessage());
        } catch (BusinessException ex) {
            recordFailure(
                    prepared.tenantId(), prepared.userId(), prepared.conversationId(), execution.userMessageId(),
                    prepared.traceId(), prepared.startedAt(),
                    ex.getErrorCode().code(), protocolEvents(prepared, ex));
            markIdempotencyFailed(prepared, ex.getErrorCode());
            throw ex;
        } catch (RuntimeException ex) {
            recordFailure(
                    prepared.tenantId(), prepared.userId(), prepared.conversationId(), execution.userMessageId(),
                    prepared.traceId(), prepared.startedAt(),
                    ErrorCode.COM_INTERNAL_001.code(),
                    List.of(auditEventFactory.failed(
                            prepared.traceId(), prepared.userId(), prepared.conversationId(),
                            latencyMs(prepared.startedAt()), ErrorCode.COM_INTERNAL_001.code())));
            markIdempotencyFailed(prepared, ErrorCode.COM_INTERNAL_001);
            throw new BusinessException(ErrorCode.COM_INTERNAL_001, "Agent execution failed", ex);
        } finally {
            clearState(prepared.traceId());
        }
    }

    /**
     * 新执行的前置步骤：幂等 tryStart、user message 落库、deadline 核算、预算与状态初始化。
     * 返回 empty 表示 tryStart 竞争失败，调用方应改走幂等重放语义。
     */
    private Optional<FreshExecution> tryStartFresh(PreparedAsk prepared) {
        if (!idempotencyStore.tryStart(
                prepared.userId(), prepared.conversationId(), prepared.key(),
                prepared.fingerprint(), clock.instant())) {
            return Optional.empty();
        }
        UUID userMessageId = UUID.randomUUID();
        Instant userMessageAt = clock.instant();
        try {
            transactionRunner.execute(() -> {
                messageRepository.insert(
                        prepared.tenantId(),
                        userMessageId,
                        prepared.conversationId(),
                        "USER",
                        prepared.question(),
                        estimateTokens(prepared.question()),
                        prepared.key(),
                        prepared.traceId(),
                        toJson(Map.of("documentIds", prepared.documentIds())),
                        userMessageAt);
                conversationRepository.touch(prepared.tenantId(), prepared.conversationId(), userMessageAt);
            });
        } catch (DuplicateKeyException ex) {
            markIdempotencyFailed(prepared, ErrorCode.AGT_CONFLICT_001);
            throw new BusinessException(
                    ErrorCode.AGT_CONFLICT_001, "This request has already been processed");
        } catch (RuntimeException ex) {
            markIdempotencyFailed(prepared, ErrorCode.COM_INTERNAL_001);
            throw new BusinessException(ErrorCode.COM_INTERNAL_001, "Unable to persist user message", ex);
        }

        Instant deadline = prepared.startedAt().plus(pythonProperties.getDeadline());
        long remainingMs = Duration.between(clock.instant(), deadline).toMillis();
        if (remainingMs < MIN_DEADLINE_MS) {
            PythonCallException expired = new PythonCallException(
                    ErrorCode.AGT_UPSTREAM_002, "Agent deadline expired", List.of(), false);
            failAfterUserMessage(prepared, userMessageId, expired);
            throw new BusinessException(expired.getErrorCode(), expired.getMessage());
        }
        budgetStore.initialize(
                prepared.traceId(),
                pythonProperties.getTokenBudget(),
                pythonProperties.getMaxToolCalls(),
                pythonProperties.getMaxRounds(),
                deadline);
        agentStateStore.initialize(prepared.traceId());
        return Optional.of(new FreshExecution(userMessageId, Math.min(MAX_DEADLINE_MS, (int) remainingMs)));
    }

    private AskData executeCached(PreparedAsk prepared) {
        if (!idempotencyStore.tryStart(
                prepared.userId(), prepared.conversationId(), prepared.key(),
                prepared.fingerprint(), clock.instant())) {
            return replayOrConflict(
                    idempotencyStore.find(prepared.userId(), prepared.conversationId(), prepared.key())
                            .orElseThrow(() -> new BusinessException(ErrorCode.AGT_CONFLICT_001)),
                    prepared.fingerprint());
        }
        CachedAnswer cached = prepared.cached().orElseThrow();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        Instant userMessageAt = clock.instant();
        Instant assistantMessageAt = userMessageAt.plusMillis(1);
        AskData result = new AskData(
                prepared.conversationId(),
                assistantMessageId,
                cached.answer(),
                cached.citations(),
                cached.confidence(),
                "OK",
                0,
                0,
                0,
                true,
                false,
                assistantMessageAt);
        List<AuditEvent> events = List.of(
                auditEventFactory.cacheHit(prepared.traceId(), prepared.userId(), prepared.conversationId()),
                auditEventFactory.completed(
                        prepared.traceId(), prepared.userId(), prepared.conversationId(),
                        latencyMs(prepared.startedAt(), assistantMessageAt),
                        0, "OK", true));
        AgentExecution execution = new AgentExecution(
                UUID.randomUUID(), prepared.tenantId(), prepared.traceId(), prepared.userId(),
                prepared.conversationId(),
                userMessageId, assistantMessageId,
                "SUCCESS", "OK", pythonProperties.getTokenBudget(), 0, 0, 0, true,
                latencyMs(prepared.startedAt(), assistantMessageAt), null, events,
                prepared.startedAt(), assistantMessageAt, assistantMessageAt);
        try {
            transactionRunner.execute(() -> {
                messageRepository.insert(
                        prepared.tenantId(), userMessageId, prepared.conversationId(), "USER", prepared.question(),
                        estimateTokens(prepared.question()),
                        prepared.key(), prepared.traceId(),
                        toJson(Map.of("documentIds", prepared.documentIds())), userMessageAt);
                messageRepository.insert(
                        prepared.tenantId(), assistantMessageId, prepared.conversationId(), "ASSISTANT",
                        cached.answer(), 0,
                        null, prepared.traceId(), toJson(Map.of("citations", cached.citations())),
                        assistantMessageAt);
                conversationRepository.touch(prepared.tenantId(), prepared.conversationId(), assistantMessageAt);
                agentExecutionRepository.insert(prepared.tenantId(), execution);
            });
        } catch (RuntimeException ex) {
            markIdempotencyFailed(prepared, ErrorCode.AGT_INTERNAL_001);
            throw new BusinessException(ErrorCode.AGT_INTERNAL_001, "Unable to persist cached answer", ex);
        }
        completeIdempotency(prepared, result);
        return result;
    }

    /**
     * assistantMessageId 传 null 时随机生成（同步路径）；流式路径必须传入 meta 帧已固定的
     * messageId，保证 done 帧与库内 assistant 消息同 ID。
     */
    private AskData persistSuccess(
            PreparedAsk prepared,
            UUID requestMessageId,
            UUID assistantMessageIdOrNull,
            PythonAskResponse response,
            ValidatedAnswer validated,
            boolean cacheHit) {
        UUID assistantMessageId = assistantMessageIdOrNull == null
                ? UUID.randomUUID()
                : assistantMessageIdOrNull;
        Instant assistantAt = clock.instant();
        String status = "BUDGET_EXHAUSTED".equals(validated.budgetStatus()) ? "BUDGET_EXHAUSTED" : "SUCCESS";
        List<AuditEvent> events = new ArrayList<>(validated.auditEvents());
        events.add(auditEventFactory.completed(
                prepared.traceId(),
                prepared.userId(),
                prepared.conversationId(),
                latencyMs(prepared.startedAt(), assistantAt),
                response.tokenUsed(),
                validated.budgetStatus(),
                cacheHit));
        AgentExecution execution = new AgentExecution(
                UUID.randomUUID(),
                prepared.tenantId(),
                prepared.traceId(),
                prepared.userId(),
                prepared.conversationId(),
                requestMessageId,
                assistantMessageId,
                status,
                validated.budgetStatus(),
                pythonProperties.getTokenBudget(),
                response.tokenUsed(),
                response.toolCallsMade(),
                response.roundsUsed(),
                cacheHit,
                latencyMs(prepared.startedAt(), assistantAt),
                null,
                List.copyOf(events),
                prepared.startedAt(),
                assistantAt,
                assistantAt);
        try {
            transactionRunner.execute(() -> {
                messageRepository.insert(
                        prepared.tenantId(),
                        assistantMessageId,
                        prepared.conversationId(),
                        "ASSISTANT",
                        response.answer(),
                        response.tokenUsed(),
                        null,
                        prepared.traceId(),
                        toJson(Map.of("citations", validated.citations())),
                        assistantAt);
                conversationRepository.touch(prepared.tenantId(), prepared.conversationId(), assistantAt);
                agentExecutionRepository.insert(prepared.tenantId(), execution);
            });
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.AGT_INTERNAL_001, "Unable to persist agent execution", ex);
        }
        return new AskData(
                prepared.conversationId(),
                assistantMessageId,
                response.answer(),
                validated.citations(),
                response.confidence(),
                validated.budgetStatus(),
                response.tokenUsed(),
                response.toolCallsMade(),
                response.roundsUsed(),
                cacheHit,
                false,
                assistantAt);
    }

    private void cacheResult(PreparedAsk prepared, PythonAskResponse response, ValidatedAnswer validated) {
        try {
            qaCacheService.put(
                    prepared.userId(),
                    prepared.documents(),
                    prepared.history(),
                    prepared.masked().masked(),
                    new CachedAnswer(response.answer(), validated.citations(), response.confidence()));
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to write QA cache: traceId={}", prepared.traceId());
        }
    }

    private void failAfterUserMessage(PreparedAsk prepared, UUID userMessageId, PythonCallException ex) {
        AuditValidation validation = auditEventValidator.validateError(
                ex.getAuditEvents(), prepared.traceId(), prepared.userId(), prepared.conversationId());
        List<AuditEvent> events = new ArrayList<>(validation.events());
        if (validation.gap() || ex.isAuditGap()) {
            events.add(auditEventFactory.auditGap(
                    prepared.traceId(), prepared.userId(), prepared.conversationId(), ex.getErrorCode().code()));
        }
        if (ex.getErrorCode() == ErrorCode.AGT_VALIDATION_003 && validation.events().isEmpty()) {
            events.add(auditEventFactory.piiBlocked(prepared.traceId(), prepared.userId(), prepared.conversationId(), 0, List.of()));
        }
        if (ex.getErrorCode() == ErrorCode.AGT_UPSTREAM_004) {
            events.add(auditEventFactory.protocolViolation(
                    prepared.traceId(), prepared.userId(), prepared.conversationId(), ex.getMessage()));
        }
        events.add(auditEventFactory.failed(
                prepared.traceId(), prepared.userId(), prepared.conversationId(),
                latencyMs(prepared.startedAt()), ex.getErrorCode().code()));
        recordFailure(
                prepared.tenantId(), prepared.userId(), prepared.conversationId(), userMessageId,
                prepared.traceId(), prepared.startedAt(), ex.getErrorCode().code(), events);
        markIdempotencyFailed(prepared, ex.getErrorCode());
    }

    private void recordFailure(
            UUID tenantId,
            UUID userId,
            UUID conversationId,
            UUID requestMessageId,
            String traceId,
            Instant startedAt,
            String errorCode,
            List<AuditEvent> events) {
        Instant finishedAt = clock.instant();
        AgentExecution execution = new AgentExecution(
                UUID.randomUUID(),
                tenantId,
                traceId,
                userId,
                conversationId,
                requestMessageId,
                null,
                "FAILED",
                null,
                pythonProperties.getTokenBudget(),
                0,
                0,
                0,
                false,
                latencyMs(startedAt, finishedAt),
                errorCode,
                List.copyOf(events),
                startedAt,
                finishedAt,
                finishedAt);
        try {
            transactionRunner.execute(() -> agentExecutionRepository.insert(tenantId, execution));
        } catch (RuntimeException ex) {
            LOGGER.error("Unable to persist failure execution: traceId={}, code={}", traceId, errorCode);
        }
    }

    private void handleKillSwitch(UUID tenantId, UUID userId, UUID conversationId, String traceId,
            Instant startedAt) {
        List<AuditEvent> events = List.of(
                auditEventFactory.killSwitch(traceId, userId, conversationId),
                auditEventFactory.failed(
                        traceId, userId, conversationId, latencyMs(startedAt), ErrorCode.AGT_UPSTREAM_003.code()));
        recordFailure(
                tenantId, userId, conversationId, null, traceId, startedAt,
                ErrorCode.AGT_UPSTREAM_003.code(), events);
        throw new BusinessException(ErrorCode.AGT_UPSTREAM_003);
    }

    private List<AuditEvent> protocolEvents(PreparedAsk prepared, BusinessException ex) {
        List<AuditEvent> events = new ArrayList<>();
        if (ex.getErrorCode() == ErrorCode.AGT_UPSTREAM_004) {
            events.add(auditEventFactory.protocolViolation(
                    prepared.traceId(), prepared.userId(), prepared.conversationId(), ex.getDetail()));
            events.add(auditEventFactory.auditGap(
                    prepared.traceId(), prepared.userId(), prepared.conversationId(), ex.getErrorCode().code()));
        }
        events.add(auditEventFactory.failed(
                prepared.traceId(), prepared.userId(), prepared.conversationId(), 0, ex.getErrorCode().code()));
        return events;
    }

    private PythonAskRequest pythonRequest(PreparedAsk prepared, int deadlineMs) {
        List<PythonDocumentScope> scopes = prepared.documents().stream()
                .map(document -> new PythonDocumentScope(document.documentId(), document.indexVersion()))
                .toList();
        List<PythonHistoryMessage> pythonHistory = prepared.history().stream()
                .map(message -> new PythonHistoryMessage(message.role(), message.piiMaskedContent()))
                .toList();
        return new PythonAskRequest(
                prepared.stream() ? PYTHON_PROTOCOL_STREAM : PYTHON_PROTOCOL_SYNC,
                prepared.tenantId(),
                prepared.userId(),
                prepared.traceId(),
                prepared.conversationId(),
                prepared.question(),
                prepared.masked().masked(),
                pythonProperties.getMaskingVersion(),
                pythonProperties.getTokenBudget(),
                pythonProperties.getMaxToolCalls(),
                pythonProperties.getMaxRounds(),
                pythonProperties.getAllowedTools(),
                deadlineMs,
                scopes,
                pythonHistory);
    }

    private List<QaCacheService.PythonHistoryMessageMask> loadHistory(UUID tenantId, UUID conversationId) {
        List<StoredMessage> messages = messageRepository.findRecent(
                tenantId, conversationId, agentProperties.getHistoryLimit());
        List<QaCacheService.PythonHistoryMessageMask> newestFirst = new ArrayList<>();
        int remaining = agentProperties.getHistoryMaxChars();
        for (int index = messages.size() - 1; index >= 0; index--) {
            StoredMessage message = messages.get(index);
            if (!"USER".equals(message.role()) && !"ASSISTANT".equals(message.role())) {
                continue;
            }
            String maskedContent = piiMasker.mask(message.content()).masked();
            if (maskedContent.length() > MAX_HISTORY_MESSAGE_CHARS) {
                maskedContent = maskedContent.substring(maskedContent.length() - MAX_HISTORY_MESSAGE_CHARS);
            }
            if (maskedContent.length() > remaining) {
                if (remaining <= 0) {
                    break;
                }
                maskedContent = maskedContent.substring(maskedContent.length() - remaining);
            }
            newestFirst.add(new QaCacheService.PythonHistoryMessageMask(message.role(), maskedContent));
            remaining -= maskedContent.length();
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    private String validateQuestion(AskRequest request) {
        if (request == null || request.question() == null || request.question().trim().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("question", "must not be blank")));
        }
        String question = request.question().trim();
        if (question.length() > 4000) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("question", "must be at most 4000 characters")));
        }
        return question;
    }

    private List<UUID> validateDocumentIds(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty() || documentIds.size() > 20) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("documentIds", "must contain 1 to 20 documents")));
        }
        Set<UUID> unique = new LinkedHashSet<>(documentIds);
        if (unique.size() != documentIds.size()) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("documentIds", "must not contain duplicates")));
        }
        return List.copyOf(documentIds);
    }

    private String fingerprint(UUID userId, UUID conversationId, String question, List<UUID> documentIds) {
        String scope = documentIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        return Hashing.sha256(userId + "|" + conversationId + "|" + question + "|" + scope);
    }

    private boolean isUsable(CachedAnswer cached) {
        return cached.answer() != null && !cached.answer().isBlank() && cached.citations() != null;
    }

    private AskData replayOrConflict(IdempotencyRecord record, String fingerprint) {
        if (record.status() == IdempotencyStatus.COMPLETED
                && fingerprint.equals(record.fingerprint())
                && record.response() != null) {
            AskData original = record.response();
            return new AskData(
                    original.conversationId(),
                    original.messageId(),
                    original.answer(),
                    original.citations(),
                    original.confidence(),
                    original.budgetStatus(),
                    original.tokenUsed(),
                    original.toolCallsMade(),
                    original.roundsUsed(),
                    original.cacheHit(),
                    true,
                    original.createdAt());
        }
        String detail = record.status() == IdempotencyStatus.IN_PROGRESS
                ? "The same Idempotency-Key is still in progress"
                : "The same Idempotency-Key was used with a different request";
        throw new BusinessException(ErrorCode.AGT_CONFLICT_001, detail);
    }

    private void completeIdempotency(PreparedAsk prepared, AskData result) {
        try {
            idempotencyStore.complete(
                    prepared.userId(), prepared.conversationId(), prepared.key(),
                    prepared.fingerprint(), result, clock.instant());
        } catch (RuntimeException ex) {
            LOGGER.error("Unable to complete idempotency record: conversationId={}", prepared.conversationId());
        }
    }

    private void markIdempotencyFailed(PreparedAsk prepared, ErrorCode errorCode) {
        try {
            idempotencyStore.fail(
                    prepared.userId(), prepared.conversationId(), prepared.key(),
                    prepared.fingerprint(), errorCode.code(), clock.instant());
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to mark idempotency record failed");
        }
    }

    private void clearState(String traceId) {
        try {
            agentStateStore.clear(traceId);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to clear agent state: traceId={}", traceId);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize message metadata", ex);
        }
    }

    private int estimateTokens(String value) {
        return Math.max(1, value.length() / 4);
    }

    private long latencyMs(Instant startedAt) {
        return latencyMs(startedAt, clock.instant());
    }

    private long latencyMs(Instant startedAt, Instant finishedAt) {
        return Math.max(0, Duration.between(startedAt, finishedAt).toMillis());
    }

    /**
     * 一次问答的终态记账（§4.2 的 qa_requests_total / qa_latency_seconds）。
     *
     * <p>{@code data == null} 表示请求在拿到结果之前就失败了——限流、校验、幂等冲突、上游异常
     * 都走这里，一律记 {@code FAILED}。这正是「按终态计数」的含义：失败率的分母必须包含全部
     * 请求，而不是只包含走到了 Python 的那些。
     *
     * <p>{@code budget_status} 用 {@code NULL} 而不是空串表示「没有预算结论」（例如请求被限流
     * 拒绝时根本没算过预算），由门面折叠成标签值 {@code NULL}。
     */
    private void recordQaOutcome(Instant startedAt, AskData data) {
        boolean cacheHit = data != null && data.cacheHit();
        String budgetStatus = data == null ? null : data.budgetStatus();
        String status;
        if (data == null) {
            status = "FAILED";
        } else if ("BUDGET_EXHAUSTED".equals(budgetStatus)) {
            status = "BUDGET_EXHAUSTED";
        } else {
            status = "SUCCESS";
        }
        metrics.qaRequest(status, budgetStatus, cacheHit);
        metrics.qaLatency(Duration.between(startedAt, clock.instant()), status, cacheHit);
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record FreshExecution(UUID userMessageId, int deadlineMs) {
    }

    /**
     * 流式会话（meta 已下发之后）的事件驱动收尾：done 帧走与非流式完全一致的
     * recordTokens → validate → 一次事务落库 → 缓存 → 幂等完成，再发 done 帧；
     * 一切失败以 error 帧终止并记 FAILED；断线 / 超时 / 发送失败统一清理上游订阅、
     * 状态与锁。全部回调可能在 reactor 线程触发，不得依赖 ThreadLocal traceId。
     */
    private final class StreamSession {
        private final PreparedAsk prepared;
        private final SseEmitter emitter;
        private final UUID userMessageId;
        private final UUID assistantMessageId;
        private final AtomicBoolean terminated = new AtomicBoolean(false);
        private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
        private volatile Disposable upstream;
        private volatile ScheduledFuture<?> keepAlive;
        /**
         * 终态由 done 帧的成功落库改写；其余一切收尾（error 帧、断线、超时、上游提前结束）
         * 保持默认 FAILED。只由 cleanup 读取一次，避免在多个回调里各记一笔。
         */
        private volatile String outcome = "FAILED";

        private volatile String budgetStatus = null;

        private StreamSession(
                PreparedAsk prepared, SseEmitter emitter, UUID userMessageId, UUID assistantMessageId) {
            this.prepared = prepared;
            this.emitter = emitter;
            this.userMessageId = userMessageId;
            this.assistantMessageId = assistantMessageId;
        }

        private void begin(Flux<ServerSentEvent<String>> events) {
            keepAlive = sseKeepAliveScheduler.scheduleAtFixedRate(
                    this::sendKeepAlive, Instant.now().plus(KEEP_ALIVE_INTERVAL), KEEP_ALIVE_INTERVAL);
            emitter.onTimeout(this::onTimeout);
            emitter.onCompletion(this::onCompletion);
            if (!sendEvent("meta", metaJson())) {
                // meta 即发送失败说明客户端已不可达：记 FAILED 并释放资源，不再订阅上游
                onClientGone();
                return;
            }
            upstream = events.subscribe(this::onEvent, this::onStreamError, this::onStreamComplete);
        }

        private void onEvent(ServerSentEvent<String> event) {
            if (terminated.get()) {
                return;
            }
            String name = event.event();
            if ("delta".equals(name)) {
                sendDelta(event.data());
                return;
            }
            if ("done".equals(name)) {
                onDone(event.data());
                return;
            }
            if ("error".equals(name)) {
                onPythonError(event.data());
                return;
            }
            onFailure(new PythonCallException(
                    ErrorCode.AGT_UPSTREAM_004,
                    "Python stream sent an unexpected event: " + name,
                    List.of(),
                    true));
        }

        private void sendDelta(String data) {
            if (!sendEvent("delta", deltaJson(data))) {
                onClientGone();
            }
        }

        private void onDone(String data) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            try {
                PythonAskResponse response = pythonAgentClient.parseStreamPayload(data);
                quotaGuard.recordTokens(prepared.userId(), response.tokenUsed());
                BudgetSnapshot budget = budgetStore.snapshot(prepared.traceId()).orElse(null);
                ValidatedAnswer validated = responseValidator.validate(
                        response, prepared.traceId(), prepared.userId(), prepared.conversationId(),
                        prepared.documents(), budget);
                AskData result = persistSuccess(
                        prepared, userMessageId, assistantMessageId, response, validated, false);
                if ("OK".equals(validated.budgetStatus())) {
                    cacheResult(prepared, response, validated);
                }
                completeIdempotency(prepared, result);
                budgetStatus = result.budgetStatus();
                outcome = "BUDGET_EXHAUSTED".equals(budgetStatus) ? "BUDGET_EXHAUSTED" : "SUCCESS";
                sendEvent("done", doneJson(result, prepared.traceId()));
                completeEmitter();
            } catch (BusinessException ex) {
                // 落库 / 校验失败：与非流式 executeFresh 的 BusinessException 分支逐字段一致，
                // 但 meta 已发，只能以 error 帧表达
                recordFailure(
                        prepared.tenantId(), prepared.userId(), prepared.conversationId(), userMessageId,
                        prepared.traceId(), prepared.startedAt(),
                        ex.getErrorCode().code(), protocolEvents(prepared, ex));
                markIdempotencyFailed(prepared, ex.getErrorCode());
                sendErrorFrame(ex.getErrorCode(), ex.getMessage());
                completeEmitter();
            } catch (RuntimeException ex) {
                recordFailure(
                        prepared.tenantId(), prepared.userId(), prepared.conversationId(), userMessageId,
                        prepared.traceId(), prepared.startedAt(),
                        ErrorCode.COM_INTERNAL_001.code(),
                        List.of(auditEventFactory.failed(
                                prepared.traceId(), prepared.userId(), prepared.conversationId(),
                                latencyMs(prepared.startedAt()), ErrorCode.COM_INTERNAL_001.code())));
                markIdempotencyFailed(prepared, ErrorCode.COM_INTERNAL_001);
                sendErrorFrame(ErrorCode.COM_INTERNAL_001, "Agent execution failed");
                completeEmitter();
            } finally {
                cleanup();
            }
        }

        private void onPythonError(String data) {
            ErrorCode code = ErrorCode.AGT_UPSTREAM_001;
            String detail = "Python stream reported an error";
            try {
                JsonNode node = data == null ? null : objectMapper.readTree(data);
                if (node != null && node.hasNonNull("code")) {
                    code = ErrorCode.find(node.get("code").asText()).orElse(ErrorCode.AGT_UPSTREAM_001);
                }
                if (node != null && node.hasNonNull("detail")) {
                    detail = node.get("detail").asText();
                }
            } catch (JsonProcessingException ex) {
                code = ErrorCode.AGT_UPSTREAM_004;
                detail = "Python stream error payload is not valid JSON";
            }
            onFailure(new PythonCallException(code, detail, List.of(), true));
        }

        private void onStreamError(Throwable error) {
            if (terminated.get()) {
                return;
            }
            PythonCallException failure;
            if (error instanceof PythonCallException pythonCallException) {
                failure = pythonCallException;
            } else if (isTimeout(error)) {
                failure = new PythonCallException(
                        ErrorCode.AGT_UPSTREAM_002, "Python stream timed out", List.of(), false);
            } else {
                failure = new PythonCallException(
                        ErrorCode.AGT_UPSTREAM_001, "Python stream failed", List.of(), false);
            }
            onFailure(failure);
        }

        private void onStreamComplete() {
            if (terminated.get()) {
                return;
            }
            onFailure(new PythonCallException(
                    ErrorCode.AGT_UPSTREAM_004, "Python stream ended without a done event", List.of(), true));
        }

        private void onTimeout() {
            if (!terminated.compareAndSet(false, true)) {
                cleanup();
                return;
            }
            PythonCallException timeout = new PythonCallException(
                    ErrorCode.AGT_UPSTREAM_002, "SSE stream timed out", List.of(), false);
            try {
                failAfterUserMessage(prepared, userMessageId, timeout);
                sendErrorFrame(timeout.getErrorCode(), timeout.getMessage());
                completeEmitter();
            } finally {
                cleanup();
            }
        }

        private void onCompletion() {
            if (terminated.compareAndSet(false, true)) {
                // 客户端断开：记 FAILED、幂等标记失败（同 key 可重试），配额不退还
                failAfterUserMessage(prepared, userMessageId, new PythonCallException(
                        ErrorCode.AGT_UPSTREAM_002, "SSE client disconnected", List.of(), false));
            }
            cleanup();
        }

        private void onClientGone() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            failAfterUserMessage(prepared, userMessageId, new PythonCallException(
                    ErrorCode.AGT_UPSTREAM_002, "SSE client disconnected", List.of(), false));
            cleanup();
        }

        private void onFailure(PythonCallException failure) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            try {
                failAfterUserMessage(prepared, userMessageId, failure);
                sendErrorFrame(failure.getErrorCode(), failure.getMessage());
                completeEmitter();
            } finally {
                cleanup();
            }
        }

        private void sendErrorFrame(ErrorCode errorCode, String detail) {
            ProblemDetail problem = ProblemDetailFactory.create(
                    errorCode,
                    detail,
                    null,
                    "/api/v1/conversations/" + prepared.conversationId() + "/ask:stream");
            // 回调可能运行在 reactor 线程，ThreadLocal 中没有请求 traceId，必须显式覆盖
            problem.setProperty("traceId", prepared.traceId());
            sendEvent("error", toJson(problem));
        }

        private String metaJson() {
            return toJson(new AskStreamMetaPayload(
                    prepared.conversationId(), assistantMessageId, prepared.traceId(), false));
        }

        private boolean sendEvent(String eventName, String json) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
                return true;
            } catch (IOException | IllegalStateException ex) {
                return false;
            }
        }

        private void sendKeepAlive() {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException ex) {
                // 连接已失效：onCompletion 或下一帧发送失败路径负责收尾
            }
        }

        private void completeEmitter() {
            try {
                emitter.complete();
            } catch (IllegalStateException ex) {
                // 已被 complete / timeout 先行结束：忽略
            }
        }

        private void cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) {
                return;
            }
            if (keepAlive != null) {
                keepAlive.cancel(false);
            }
            Disposable subscription = upstream;
            if (subscription != null) {
                subscription.dispose();
            }
            clearState(prepared.traceId());
            if (prepared.lockHeld()) {
                conversationLock.unlock(prepared.conversationId(), prepared.traceId());
            }
            // 流式路径不读问答缓存（缓存命中走 completeImmediateStream 的短流），cache_hit 恒 false。
            metrics.qaRequest(outcome, budgetStatus, false);
            metrics.qaLatency(Duration.between(prepared.startedAt(), clock.instant()), outcome, false);
        }
    }
}
