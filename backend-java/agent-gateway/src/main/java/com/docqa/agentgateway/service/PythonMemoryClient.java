package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.PythonProperties;
import com.docqa.agentgateway.dto.MemoryListData;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 长期记忆管理面的数据面代理：照 {@link WebClientPythonAgentClient} 的请求形态
 * （同一个 pythonWebClient ⇒ 同一个 X-Internal-Token 默认头；traceparent 同形），
 * 但刻意不挂问答熔断器——管理面的读删与问答可用性是两条故障域，管理页失败不该烧问答的熔断计数。
 *
 * <p>租户谓词由网关从已验签 JWT 附加，userId 是**被治理用户**而不是调用者；
 * 数据面按 (tenantId, userId) 命名空间隔离，跨租户的 key 一律 404。
 */
@Component
public class PythonMemoryClient {

    private final WebClient webClient;
    private final PythonProperties properties;
    private final ObjectMapper objectMapper;

    public PythonMemoryClient(WebClient pythonWebClient, PythonProperties properties, ObjectMapper objectMapper) {
        this.webClient = pythonWebClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public MemoryListData list(UUID tenantId, UUID userId, String traceId) {
        try {
            JsonNode body = webClient.get()
                    .uri("/internal/memories?tenantId={tenantId}&userId={userId}", tenantId, userId)
                    .header("X-Trace-Id", traceId)
                    .header("traceparent", traceparent(traceId))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(properties.getResponseTimeout().plusMillis(500));
            if (body == null || !body.has("items") || !body.get("items").isArray()) {
                throw new BusinessException(ErrorCode.AGT_UPSTREAM_004, "Python memory response is missing items");
            }
            return objectMapper.treeToValue(body, MemoryListData.class);
        } catch (WebClientResponseException ex) {
            throw mapHttpError(ex.getStatusCode().value());
        } catch (WebClientRequestException ex) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "Python is unreachable", ex);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_004, "Python memory response is not valid JSON", ex);
        } catch (IllegalStateException ex) {
            throw blockTimeout(ex);
        }
    }

    /** 返回 false 表示数据面没有这条记忆（404）；其余失败按错误码抛出，不静默当成已删除。 */
    public boolean delete(UUID tenantId, UUID userId, UUID memoryKey, String traceId) {
        try {
            Integer status = webClient.delete()
                    .uri("/internal/memories/{key}?tenantId={tenantId}&userId={userId}", memoryKey, tenantId, userId)
                    .header("X-Trace-Id", traceId)
                    .header("traceparent", traceparent(traceId))
                    .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()))
                    .block(properties.getResponseTimeout().plusMillis(500));
            if (status == null) {
                throw new BusinessException(ErrorCode.AGT_UPSTREAM_004, "Python memory delete returned no status");
            }
            if (status == 204) {
                return true;
            }
            if (status == 404) {
                return false;
            }
            throw mapHttpError(status);
        } catch (WebClientRequestException ex) {
            throw new BusinessException(ErrorCode.AGT_UPSTREAM_001, "Python is unreachable", ex);
        } catch (IllegalStateException ex) {
            throw blockTimeout(ex);
        }
    }

    private BusinessException mapHttpError(int status) {
        if (status == 504) {
            return new BusinessException(ErrorCode.AGT_UPSTREAM_002, "Python memory call timed out");
        }
        if (status == 500 || status == 502 || status == 503) {
            return new BusinessException(ErrorCode.AGT_UPSTREAM_001, "Python memory surface is unavailable");
        }
        return new BusinessException(ErrorCode.AGT_UPSTREAM_004, "Python returned an unexpected status " + status);
    }

    private BusinessException blockTimeout(IllegalStateException ex) {
        String message = ex.getMessage();
        if (message != null && message.toLowerCase().contains("timeout")) {
            return new BusinessException(ErrorCode.AGT_UPSTREAM_002, "Python memory call timed out", ex);
        }
        return new BusinessException(ErrorCode.AGT_UPSTREAM_001, "Python memory call failed", ex);
    }

    private String traceparent(String traceId) {
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }
}