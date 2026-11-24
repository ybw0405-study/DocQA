package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.config.PythonProperties;
import com.docqa.agentgateway.dto.PythonAskRequest;
import com.docqa.agentgateway.dto.PythonAskResponse;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.metrics.BusinessMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

class WebClientPythonAgentClientTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String VALID_RESPONSE = """
            {
              "protocolVersion": "1.0",
              "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
              "answer": "答案",
              "citations": [],
              "confidence": 0.9,
              "budgetStatus": "OK",
              "tokenUsed": 10,
              "toolCallsMade": 0,
              "roundsUsed": 1,
              "auditEvents": [],
              "finishedAt": "2026-01-01T00:00:00Z"
            }
            """;

    private PythonProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new PythonProperties();
        properties.setResponseTimeout(Duration.ofSeconds(2));
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void sendsGovernanceHeadersAndParsesResponse() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClientPythonAgentClient client = client(request -> {
            captured.set(request);
            return Mono.just(json(HttpStatus.OK, VALID_RESPONSE));
        });

        PythonAskResponse response = client.ask(request(), TRACE_ID);

        assertThat(response.answer()).isEqualTo("答案");
        assertThat(response.budgetStatus()).isEqualTo("OK");
        assertThat(captured.get().headers().getFirst("X-Trace-Id")).isEqualTo(TRACE_ID);
        assertThat(captured.get().headers().getFirst("traceparent"))
                .matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");
        assertThat(captured.get().url().getPath()).isEqualTo("/agent/ask");
    }

    @Test
    void mapsPiiBlockToValidationError() {
        WebClientPythonAgentClient client = client(request ->
                Mono.just(json(HttpStatus.UNPROCESSABLE_ENTITY,
                        "{\"code\":\"AGT-VALIDATION-003\",\"auditEvents\":[]}")));

        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_VALIDATION_003);
    }

    @Test
    void mapsUnexpected422ToProtocolViolation() {
        WebClientPythonAgentClient client = client(request ->
                Mono.just(json(HttpStatus.UNPROCESSABLE_ENTITY, "{\"code\":\"OTHER-001\"}")));

        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_004);
    }

    @Test
    void maps504ToTimeout() {
        WebClientPythonAgentClient client = client(request ->
                Mono.just(ClientResponse.create(HttpStatus.GATEWAY_TIMEOUT).build()));

        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_002);
    }

    @Test
    void maps503ToUnavailable() {
        WebClientPythonAgentClient client = client(request ->
                Mono.just(json(HttpStatus.SERVICE_UNAVAILABLE, "{\"code\":\"AGT-UPSTREAM-001\"}")));

        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_001);
    }

    @Test
    void maps500ToUnavailable() {
        WebClientPythonAgentClient client = client(request ->
                Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()));

        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_001);
    }

    @Test
    void mapsUnexpectedStatusToProtocolViolation() {
        WebClientPythonAgentClient client = client(request ->
                Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST).build()));

        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_004);
    }

    @Test
    void mapsIncompleteResponseToProtocolViolation() {
        WebClientPythonAgentClient client = client(request ->
                Mono.just(json(HttpStatus.OK, "{}")));

        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_004);
    }

    @Test
    void mapsConnectionFailureToUnavailable() {
        WebClientPythonAgentClient client = client(request -> Mono.error(
                new WebClientRequestException(
                        new RuntimeException("connection refused"),
                        HttpMethod.POST,
                        URI.create("http://127.0.0.1:8000/agent/ask"),
                        HttpHeaders.EMPTY)));

        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_001);
    }

    @Test
    void mapsTimeoutSignalToTimeout() {
        WebClientPythonAgentClient client = client(request ->
                Mono.error(new TimeoutException("request timeout")));

        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_002);
    }

    @Test
    void opensCircuitBreakerAfterRepeatedFailures() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(1)
                .slidingWindowSize(2)
                .failureRateThreshold(1.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        WebClientPythonAgentClient client = client(
                request -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()),
                registry);

        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> client.ask(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .hasMessageContaining("circuit breaker is open");
    }

    @Test
    void openStreamReturnsParsedEventFlux() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClientPythonAgentClient client = client(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body("event:delta\ndata:{\"text\":\"片段\"}\n\nevent:done\ndata:{\"x\":1}\n\n")
                    .build());
        });

        List<ServerSentEvent<String>> events = client.openStream(request(), TRACE_ID).collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).event()).isEqualTo("delta");
        assertThat(events.get(0).data()).isEqualTo("{\"text\":\"片段\"}");
        assertThat(events.get(1).event()).isEqualTo("done");
        assertThat(captured.get().url().getPath()).isEqualTo("/agent/ask/stream");
        assertThat(captured.get().headers().getFirst(HttpHeaders.ACCEPT))
                .isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(captured.get().headers().getFirst("X-Trace-Id")).isEqualTo(TRACE_ID);
    }

    @Test
    void openStreamMaps503ToUnavailable() {
        WebClientPythonAgentClient client = client(request ->
                Mono.just(json(HttpStatus.SERVICE_UNAVAILABLE, "{\"code\":\"AGT-UPSTREAM-001\"}")));

        assertThatThrownBy(() -> client.openStream(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_001);
    }

    @Test
    void openStreamMaps504ToTimeout() {
        WebClientPythonAgentClient client = client(request ->
                Mono.just(ClientResponse.create(HttpStatus.GATEWAY_TIMEOUT).build()));

        assertThatThrownBy(() -> client.openStream(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_002);
    }

    @Test
    void openStreamMapsTimeoutSignalToTimeout() {
        WebClientPythonAgentClient client = client(request ->
                Mono.error(new TimeoutException("request timeout")));

        assertThatThrownBy(() -> client.openStream(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_002);
    }

    @Test
    void openStreamOpensDedicatedCircuitBreakerAfterRepeatedFailures() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(1)
                .slidingWindowSize(2)
                .failureRateThreshold(1.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        WebClientPythonAgentClient client = client(request -> {
            if (MediaType.TEXT_EVENT_STREAM_VALUE.equals(request.headers().getFirst(HttpHeaders.ACCEPT))) {
                return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
            }
            return Mono.just(json(HttpStatus.OK, VALID_RESPONSE));
        }, registry);

        assertThatThrownBy(() -> client.openStream(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> client.openStream(request(), TRACE_ID))
                .isInstanceOf(PythonCallException.class)
                .hasMessageContaining("circuit breaker is open");
        // 同步 ask 通道走独立熔断器，流式失败不影响其准入
        PythonAskResponse response = client.ask(request(), TRACE_ID);
        assertThat(response.answer()).isEqualTo("答案");
    }

    @Test
    void parseStreamPayloadRejectsMissingFields() {
        WebClientPythonAgentClient client = client(
                request -> Mono.just(json(HttpStatus.OK, "{}")));

        assertThatThrownBy(() -> client.parseStreamPayload("{}"))
                .isInstanceOf(PythonCallException.class)
                .extracting(ex -> ((PythonCallException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_004);
    }

    @Test
    void parseStreamPayloadParsesDonePayload() {
        WebClientPythonAgentClient client = client(
                request -> Mono.just(json(HttpStatus.OK, "{}")));

        PythonAskResponse response = client.parseStreamPayload(VALID_RESPONSE);

        assertThat(response.protocolVersion()).isEqualTo("1.0");
        assertThat(response.answer()).isEqualTo("答案");
        assertThat(response.tokenUsed()).isEqualTo(10);
    }

    private WebClientPythonAgentClient client(ExchangeFunction exchange) {
        return client(exchange, CircuitBreakerRegistry.ofDefaults());
    }

    private WebClientPythonAgentClient client(
            ExchangeFunction exchange,
            CircuitBreakerRegistry registry) {
        WebClient webClient = WebClient.builder().exchangeFunction(exchange).build();
        return new WebClientPythonAgentClient(
                webClient, properties, registry, objectMapper,
                new BusinessMetrics(new SimpleMeterRegistry()));
    }

    private ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private PythonAskRequest request() {
        return new PythonAskRequest(
                "1.0",
                TENANT_ID,
                USER_ID,
                TRACE_ID,
                CONVERSATION_ID,
                "问题",
                "问题",
                "pii-v1",
                8000,
                3,
                3,
                List.of("document_search"),
                15000,
                List.of(),
                List.of(),
                null);
    }
}
