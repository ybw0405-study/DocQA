package com.docqa.common.tenant;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * 当前请求的租户身份。与 TraceIdContext 同范式，但强制：未设置时读取即失败，
 * 因为「没有租户上下文就查库」正是跨租户数据泄漏的入口。
 *
 * 值由已验签的 JWT claim 写入，不接受 Header / Body 传参；过滤器必须在 finally 中 clear。
 * set/clear 同时镜像到 SLF4J MDC，供日志模式以 %X{tenantId} 渲染租户标签；
 * 任何设置租户上下文的链路（HTTP 过滤器、MQ 消费者、定时任务）都自动获得可观测性。
 */
public final class TenantContext {

    /** 存量数据迁移期所有用户所属租户；来自 V10 迁移的固定 UUID。 */
    public static final UUID DEFAULT_TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** JWT 中承载租户身份的 claim 名，签发方与所有验签方必须一致。 */
    public static final String CLAIM_NAME = "tenant_id";

    /** Servlet request attribute 名，供控制器与过滤器读取。 */
    public static final String REQUEST_ATTRIBUTE = "docqa.tenantId";

    /** SLF4J MDC key，日志模式用 %X{tenantId} 渲染，与 traceId 对称。 */
    public static final String MDC_KEY = "tenantId";

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        CURRENT.set(tenantId);
        MDC.put(MDC_KEY, tenantId.toString());
    }

    /**
     * 读取当前租户；未设置时抛错而不是返回默认租户。
     * 默认租户只在签发与迁移脚本中使用，绝不能成为「上下文丢失时的静默兜底」。
     */
    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("tenant context is missing; request must be authenticated");
        }
        return tenantId;
    }

    public static UUID currentOrNull() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }
}
