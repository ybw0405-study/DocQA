package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.ModelConnectionProperties;
import com.docqa.agentgateway.dto.ModelProbeData;
import com.docqa.agentgateway.dto.ModelProbeFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.netty.http.client.HttpClient;

/**
 * 模型连接的连通性探测：服务端解密凭据后真实出网 GET {@code {base}/models}。
 *
 * <p>三条边界：
 * <ul>
 *   <li>出网前过 {@link LlmEgressPolicy}，界面填进来的地址在这里仍是不受信输入；</li>
 *   <li>响应体有字节上限，模型清单有条数与长度上限，避免对端把网关撑爆；</li>
 *   <li>不跟随重定向（Reactor Netty 默认行为），否则白名单校验会被一次 302 绕过。</li>
 * </ul>
 *
 * <p>任何失败都只回 {@link ModelProbeFailure} 与可读原因，**不回显密钥**，
 * 也不把对端响应体原样透传（里面可能带账号信息）。
 */
@Component
public class ModelProbeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelProbeClient.class);
    private static final int MAX_MODELS = 200;
    private static final int MAX_MODEL_ID_CHARS = 128;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final LlmEgressPolicy egressPolicy;
    private final ModelConnectionProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient client;

    public ModelProbeClient(
            LlmEgressPolicy egressPolicy,
            ModelConnectionProperties properties,
            ObjectMapper objectMapper,
            WebClient.Builder builder) {
        this.egressPolicy = egressPolicy;
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(CONNECT_TIMEOUT.toMillis()))
                .responseTimeout(properties.getProbeTimeout());
        this.client = builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(properties.getProbeMaxBytes()))
                .build();
    }

    /** apiKey 为 null 或空表示这条连接还没录入凭据；本地无 Key 端点由调用方决定是否允许跳过探测。 */
    public ModelProbeData probe(String baseUrl, String apiKey) {
        LlmEgressPolicy.ValidatedEndpoint endpoint;
        try {
            endpoint = egressPolicy.validate(baseUrl);
        } catch (LlmEgressPolicy.EgressRejected ex) {
            return ModelProbeData.failure(ModelProbeFailure.EGRESS_BLOCKED, ex.getMessage());
        }
        if (apiKey == null || apiKey.isBlank()) {
            return ModelProbeData.failure(ModelProbeFailure.CREDENTIAL_MISSING, "这条连接还没有保存 API Key");
        }
        String url = endpoint.baseUrl() + "/models";
        try {
            ModelProbeData result = client.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .exchangeToMono(response -> {
                        HttpStatusCode status = response.statusCode();
                        if (status.isError()) {
                            return response.releaseBody().thenReturn(ModelProbeData.failure(
                                    status.is4xxClientError() ? ModelProbeFailure.HTTP_4XX : ModelProbeFailure.HTTP_5XX,
                                    "端点返回 HTTP " + status.value()));
                        }
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(this::parseModels);
                    })
                    .timeout(properties.getProbeTimeout())
                    .block();
            return result == null
                    ? ModelProbeData.failure(ModelProbeFailure.NOT_OPENAI_COMPATIBLE, "端点没有返回响应体")
                    : result;
        } catch (RuntimeException ex) {
            ModelProbeFailure reason = classify(ex);
            LOGGER.warn("Model connection probe failed: host={}, reason={}", endpoint.host(), reason);
            return ModelProbeData.failure(reason, describe(reason));
        }
    }

    private ModelProbeData parseModels(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            return ModelProbeData.failure(ModelProbeFailure.NOT_OPENAI_COMPATIBLE, "端点响应不是合法 JSON");
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            return ModelProbeData.failure(ModelProbeFailure.NOT_OPENAI_COMPATIBLE, "端点没有返回 data 模型数组");
        }
        List<String> models = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode id = item.get("id");
            if (id == null || !id.isTextual()) {
                continue;
            }
            String value = id.asText().strip();
            if (!value.isEmpty() && value.length() <= MAX_MODEL_ID_CHARS && !models.contains(value)) {
                models.add(value);
            }
            if (models.size() >= MAX_MODELS) {
                break;
            }
        }
        if (models.isEmpty()) {
            return ModelProbeData.failure(ModelProbeFailure.NOT_OPENAI_COMPATIBLE, "端点的模型列表为空");
        }
        return ModelProbeData.success(List.copyOf(models));
    }

    private static ModelProbeFailure classify(RuntimeException ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException
                    || current instanceof io.netty.handler.timeout.ReadTimeoutException) {
                return ModelProbeFailure.TIMEOUT;
            }
            if (current instanceof DataBufferLimitException) {
                return ModelProbeFailure.NOT_OPENAI_COMPATIBLE;
            }
            if (current instanceof WebClientRequestException) {
                return ModelProbeFailure.UNREACHABLE;
            }
        }
        return ModelProbeFailure.UNREACHABLE;
    }

    private static String describe(ModelProbeFailure reason) {
        return switch (reason) {
            case TIMEOUT -> "探测超时，检查网络或换一个端点";
            case NOT_OPENAI_COMPATIBLE -> "端点响应超过大小上限或不像 OpenAI 兼容服务";
            default -> "端点连接不上，检查 Base URL 与出网环境";
        };
    }
}
