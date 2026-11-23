package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.agentgateway.config.PythonProperties;
import com.docqa.agentgateway.dto.MemoryListData;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

class PythonMemoryClientTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEMORY_KEY = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String LIST_BODY = """
            {"items":[{"key":"aaaaaaaa-0000-0000-0000-000000000001",\
            "text":"用户偏好表格","createdAt":"2026-11-22T10:12:03.004Z"}]}""";

    private PythonProperties properties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new PythonProperties();
        properties.setResponseTimeout(Duration.ofSeconds(2));
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Test
    void listsMemoriesWithTenantPredicateAndTraceHeaders() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        PythonMemoryClient client = client(request -> {
            captured.set(request);
            return Mono.just(json(HttpStatus.OK, LIST_BODY));
        });

        MemoryListData data = client.list(TENANT_ID, USER_ID, TRACE_ID);

        assertThat(data.items()).hasSize(1);
        assertThat(data.items().get(0).key()).isEqualTo(MEMORY_KEY);
        assertThat(data.items().get(0).text()).isEqualTo("用户偏好表格");
        assertThat(captured.get().url().getPath()).isEqualTo("/internal/memories");
        assertThat(captured.get().url().getQuery()).contains("tenantId=" + TENANT_ID, "userId=" + USER_ID);
        assertThat(captured.get().headers().getFirst("X-Trace-Id")).isEqualTo(TRACE_ID);
        assertThat(captured.get().headers().getFirst("traceparent"))
                .matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");
    }

    @Test
    void deleteReturnsTrueOn204AndFalseOn404() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        PythonMemoryClient deleted = client(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
        });
        assertThat(deleted.delete(TENANT_ID, USER_ID, MEMORY_KEY, TRACE_ID)).isTrue();
        assertThat(captured.get().method()).isEqualTo(HttpMethod.DELETE);
        assertThat(captured.get().url().getPath()).isEqualTo("/internal/memories/" + MEMORY_KEY);
        assertThat(captured.get().url().getQuery()).contains("tenantId=" + TENANT_ID, "userId=" + USER_ID);

        PythonMemoryClient missing = client(request ->
                Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build()));
        assertThat(missing.delete(TENANT_ID, USER_ID, MEMORY_KEY, TRACE_ID)).isFalse();
    }

    @Test
    void maps503ToUnavailableInsteadOfAnEmptyList() {
        PythonMemoryClient client = client(request ->
                Mono.just(json(HttpStatus.SERVICE_UNAVAILABLE, "{\"code\":\"AGT-UPSTREAM-001\"}")));

        assertThatThrownBy(() -> client.list(TENANT_ID, USER_ID, TRACE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_001);
    }

    @Test
    void maps504ToTimeout() {
        PythonMemoryClient client = client(request ->
                Mono.just(ClientResponse.create(HttpStatus.GATEWAY_TIMEOUT).build()));

        assertThatThrownBy(() -> client.delete(TENANT_ID, USER_ID, MEMORY_KEY, TRACE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_002);
    }

    @Test
    void mapsConnectionFailureToUnavailable() {
        PythonMemoryClient client = client(request -> Mono.error(
                new WebClientRequestException(
                        new RuntimeException("connection refused"),
                        HttpMethod.GET,
                        URI.create("http://127.0.0.1:8000/internal/memories"),
                        HttpHeaders.EMPTY)));

        assertThatThrownBy(() -> client.list(TENANT_ID, USER_ID, TRACE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_001);
    }

    @Test
    void mapsMalformedListBodyToProtocolViolation() {
        PythonMemoryClient client = client(request -> Mono.just(json(HttpStatus.OK, "{}")));

        assertThatThrownBy(() -> client.list(TENANT_ID, USER_ID, TRACE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_UPSTREAM_004);
    }

    private PythonMemoryClient client(ExchangeFunction exchangeFunction) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://127.0.0.1:8000")
                .exchangeFunction(exchangeFunction)
                .build();
        return new PythonMemoryClient(webClient, properties, objectMapper);
    }

    private static ClientResponse json(HttpStatus status, String body) {
        return ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}