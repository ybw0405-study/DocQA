package com.docqa.agentgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.config.PythonProperties;
import com.docqa.agentgateway.dto.PythonAskRequest;
import com.docqa.agentgateway.dto.PythonAskResponse;
import com.docqa.agentgateway.dto.PythonAuditEvent;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.metrics.BusinessMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Component
public class WebClientPythonAgentClient implements PythonAgentClient {

    private static final String CIRCUIT_BREAKER_NAME = "pythonAgent";
    private static final String STREAM_CIRCUIT_BREAKER_NAME = "pythonAgentStream";
    private static final String SYNC_ENDPOINT = "/agent/ask";
    private static final String STREAM_ENDPOINT = "/agent/ask/stream";
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SERVER_SENT_EVENT_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final TypeReference<List<PythonAuditEvent>> AUDIT_EVENT_LIST = new TypeReference<>() {
    };

    private final WebClient webClient;
    private final PythonProperties properties;
    private final CircuitBreaker circuitBreaker;
    private final CircuitBreaker streamCircuitBreaker;
    private final ObjectMapper objectMapper;
    private final BusinessMetrics metrics;

    public WebClientPythonAgentClient(
            WebClient pythonWebClient,
            PythonProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ObjectMapper objectMapper,
            BusinessMetrics metrics) {
        this.webClient = pythonWebClient;
        this.properties = properties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.streamCircuitBreaker = circuitBreakerRegistry.circuitBreaker(STREAM_CIRCUIT_BREAKER_NAME);
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public PythonAskResponse ask(PythonAskRequest request, String traceId) {
        if (!circuitBreaker.tryAcquirePermission()) {
            // 未取得许可不是「执行失败」，resilience4j 侧不该记 onError；但对外仍是一次失败的
            // Python 调用，§4.2 的 python_requests_total 必须把它算进分母。
            metrics.pythonRequest("FAILED", ErrorCode.AGT_UPSTREAM_001.code());
            recordBreakerState(circuitBreaker, CIRCUIT_BREAKER_NAME);
            throw new PythonCallException(
                    ErrorCode.AGT_UPSTREAM_001,
                    "Python circuit breaker is open",
                    List.of(),
                    false);
        }
        long startedAt = System.nanoTime();
        try {
            PythonAskResponse response = execute(request, traceId);
            circuitBreaker.onSuccess(System.nanoTime() - startedAt, java.util.concurrent.TimeUnit.NANOSECONDS);
            metrics.pythonRequest("SUCCESS", null);
            return response;
        } catch (PythonCallException ex) {
            circuitBreaker.onError(System.nanoTime() - startedAt, java.util.concurrent.TimeUnit.NANOSECONDS, ex);
            recordPythonFailure(SYNC_ENDPOINT, ex);
            throw ex;
        } catch (RuntimeException ex) {
            circuitBreaker.onError(System.nanoTime() - startedAt, java.util.concurrent.TimeUnit.NANOSECONDS, ex);
            PythonCallException mapped = isTimeout(ex)
                    ? timeout()
                    : new PythonCallException(ErrorCode.AGT_UPSTREAM_001, "Python call failed", List.of(), false);
            recordPythonFailure(SYNC_ENDPOINT, mapped);
            throw mapped;
        } finally {
            recordBreakerState(circuitBreaker, CIRCUIT_BREAKER_NAME);
        }
    }

    @Override
    public Flux<ServerSentEvent<String>> openStream(PythonAskRequest request, String traceId) {
        if (!streamCircuitBreaker.tryAcquirePermission()) {
            metrics.pythonRequest("FAILED", ErrorCode.AGT_UPSTREAM_001.code());
            recordBreakerState(streamCircuitBreaker, STREAM_CIRCUIT_BREAKER_NAME);
            throw new PythonCallException(
                    ErrorCode.AGT_UPSTREAM_001,
                    "Python stream circuit breaker is open",
                    List.of(),
                    false);
        }
        long startedAt = System.nanoTime();
        try {
            Flux<ServerSentEvent<String>> events = webClient.post()
                    .uri("/agent/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .header("X-Trace-Id", traceId)
                    .header("traceparent", traceparent(traceId))
                    .bodyValue(request)
                    .exchangeToMono(response -> {
                        if (response.statusCode().isError()) {
                            return response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> Mono.error(
                                            mapHttpError(response.statusCode().value(), body)));
                        }
                        return relayBody(response.bodyToFlux(SERVER_SENT_EVENT_TYPE));
                    })
                    .block(properties.getResponseTimeout().plusMillis(500));
            streamCircuitBreaker.onSuccess(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            metrics.pythonRequest("SUCCESS", null);
            return events;
        } catch (PythonCallException ex) {
            streamCircuitBreaker.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, ex);
            recordPythonFailure(STREAM_ENDPOINT, ex);
            throw ex;
        } catch (RuntimeException ex) {
            streamCircuitBreaker.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, ex);
            PythonCallException mapped = isTimeout(ex)
                    ? timeout()
                    : new PythonCallException(
                            ErrorCode.AGT_UPSTREAM_001, "Python stream call failed", List.of(), false);
            recordPythonFailure(STREAM_ENDPOINT, mapped);
            throw mapped;
        } finally {
            recordBreakerState(streamCircuitBreaker, STREAM_CIRCUIT_BREAKER_NAME);
        }
    }

    /**
     * 把 SSE body 的订阅搬进 exchange 回调**内部**。
     *
     * <p>缺陷复现（，真实链路）：原实现 {@code return Mono.just(response.bodyToFlux(...))}
     * 把 body 的 Flux 带出了 exchange 回调，而 {@code exchangeToMono} 的契约是「回调完成即释放
     * body」。结果：Python 侧的图真的跑完了（Redis 预算键 {@code roundsUsed=1}、
     * {@code tokensUsed≈3.8k}），网关却收到 0 个 delta、0 个 done，最终以 AGT-UPSTREAM-004
     * 「Python stream ended without a done event」失败。现象是「后端白干活、用户看不到任何
     * 输出」，且没有任何一行日志指向真正的释放点——它不在异常栈里，在响应式契约里。
     *
     * <p>修法：趁回调仍然持有连接时就把 body 订阅掉，写进一个 unicast sink；调用方拿到的
     * Flux 订阅的是 sink，与连接生命周期解耦。unicast sink 在首个订阅者到达前会缓存元素与
     * 终态信号，因此「先 block 拿到 Flux、再由 StreamSession 订阅」的既有顺序不变，meta 帧
     * 先发与首字节前的 JSON 错误语义都不受影响。
     *
     * <p>取消要向上游传播：客户端断开时 {@code StreamSession} 取消的是 sink 的 Flux，
     * 若不处置上游订阅，Python 的图会继续跑到 deadline，白烧 token（的相反面）。
     */
    private static Mono<Flux<ServerSentEvent<String>>> relayBody(Flux<ServerSentEvent<String>> body) {
        Sinks.Many<ServerSentEvent<String>> relay = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<Disposable> upstream = new AtomicReference<>(
                body.subscribe(relay::tryEmitNext, relay::tryEmitError, relay::tryEmitComplete));
        return Mono.just(relay.asFlux().doOnCancel(() -> {
            Disposable disposable = upstream.getAndSet(null);
            if (disposable != null) {
                disposable.dispose();
            }
        }));
    }

    /**
     * 失败记账。超时单独再计一笔 {@code python_request_timeout_total}：§4.2 要求超时率的分母
     * 是全部请求，所以这里既计 status=FAILED 也不跳过 timeout 序列，两条序列互为子集关系。
     */
    private void recordPythonFailure(String endpoint, PythonCallException failure) {
        metrics.pythonRequest("FAILED", failure.getErrorCode().code());
        if (failure.getErrorCode() == ErrorCode.AGT_UPSTREAM_002) {
            metrics.pythonTimeout(endpoint);
        }
    }

    private void recordBreakerState(CircuitBreaker breaker, String name) {
        metrics.circuitBreakerState(name, breaker.getState().name());
    }

    @Override
    public PythonAskResponse parseStreamPayload(String data) {
        try {
            JsonNode body = objectMapper.readTree(data);
            if (body == null || !requiredFieldsPresent(body)) {
                throw protocolFailure("Python stream payload is missing required fields", true);
            }
            return objectMapper.treeToValue(body, PythonAskResponse.class);
        } catch (JsonProcessingException ex) {
            throw protocolFailure("Python stream payload is not valid JSON", true);
        }
    }

    private PythonAskResponse execute(PythonAskRequest request, String traceId) {
        try {
            JsonNode body = webClient.post()
                    .uri("/agent/ask")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Trace-Id", traceId)
                    .header("traceparent", traceparent(traceId))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(properties.getResponseTimeout().plusMillis(500));
            return parseResponse(body);
        } catch (WebClientResponseException ex) {
            throw mapHttpError(ex);
        } catch (WebClientRequestException ex) {
            if (isTimeout(ex)) {
                throw timeout();
            }
            throw new PythonCallException(ErrorCode.AGT_UPSTREAM_001, "Python is unreachable", List.of(), false);
        } catch (JsonProcessingException ex) {
            throw protocolFailure("Python response is not valid JSON", true);
        }
    }

    private PythonAskResponse parseResponse(JsonNode body) throws JsonProcessingException {
        if (body == null || !requiredFieldsPresent(body)) {
            throw protocolFailure("Python response is missing required fields", true);
        }
        return objectMapper.treeToValue(body, PythonAskResponse.class);
    }

    private boolean requiredFieldsPresent(JsonNode body) {
        return body.hasNonNull("protocolVersion")
                && body.hasNonNull("traceId")
                && body.hasNonNull("answer")
                && body.has("citations")
                && body.get("citations").isArray()
                && body.hasNonNull("confidence")
                && body.hasNonNull("budgetStatus")
                && body.hasNonNull("tokenUsed")
                && body.hasNonNull("toolCallsMade")
                && body.hasNonNull("roundsUsed")
                && body.has("auditEvents")
                && body.get("auditEvents").isArray()
                && body.hasNonNull("finishedAt");
    }

    private PythonCallException mapHttpError(WebClientResponseException ex) {
        return mapHttpError(ex.getStatusCode().value(), ex.getResponseBodyAsString());
    }

    private PythonCallException mapHttpError(int status, String body) {
        ParsedError parsed = parseError(body);
        if (status == 422 && "AGT-VALIDATION-003".equals(parsed.code())) {
            return new PythonCallException(
                    ErrorCode.AGT_VALIDATION_003,
                    "Python blocked sensitive data",
                    parsed.auditEvents(),
                    parsed.malformed());
        }
        if (status == 422) {
            return protocolFailure("Python returned an unexpected 422", true);
        }
        if (status == 504) {
            return timeout();
        }
        if (status == 500 || status == 502 || status == 503) {
            return new PythonCallException(
                    ErrorCode.AGT_UPSTREAM_001,
                    "Python is unavailable",
                    parsed.auditEvents(),
                    parsed.malformed());
        }
        return protocolFailure("Python returned an unexpected status " + status, parsed.malformed());
    }

    private ParsedError parseError(String body) {
        if (body == null || body.isBlank()) {
            return new ParsedError(null, List.of(), true);
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String code = node.hasNonNull("code") ? node.get("code").asText() : null;
            List<PythonAuditEvent> events = List.of();
            boolean malformed = false;
            if (node.has("auditEvents") && node.get("auditEvents").isArray()) {
                try {
                    events = objectMapper.convertValue(node.get("auditEvents"), AUDIT_EVENT_LIST);
                } catch (IllegalArgumentException ex) {
                    malformed = true;
                }
            }
            return new ParsedError(code, events, malformed);
        } catch (JsonProcessingException ex) {
            return new ParsedError(null, List.of(), true);
        }
    }

    private PythonCallException protocolFailure(String detail, boolean auditGap) {
        return new PythonCallException(ErrorCode.AGT_UPSTREAM_004, detail, List.of(), auditGap);
    }

    private PythonCallException timeout() {
        return new PythonCallException(ErrorCode.AGT_UPSTREAM_002, "Python call timed out", List.of(), false);
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
            if (current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String traceparent(String traceId) {
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private record ParsedError(String code, List<PythonAuditEvent> auditEvents, boolean malformed) {
    }
}
