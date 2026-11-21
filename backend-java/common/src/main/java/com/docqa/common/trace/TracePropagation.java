package com.docqa.common.trace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方案 4.3：Java→MQ→Python 的 W3C trace 传播骨架（纯工具，不碰业务边界）。
 *
 * <p>控制面已有 {@link TraceIdContext}（MDC）与 {@code TraceIdFilter}（入站 traceparent 解析）。
 * 这里补上出站侧：把当前 traceId 写成 W3C {@code traceparent} 消息头，供 MQ envelope 注入；
 * Python 侧用 {@code docqa_ai.tracing.context_from_headers} 提取，接上同一条 trace。
 * 本类只做字符串编解码，不建 span、不改 MDC、不碰任何业务事件结构。
 */
public final class TracePropagation {

    public static final String TRACEPARENT = "traceparent";

    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final String ZERO_SPAN_ID = "0000000000000000";

    private TracePropagation() {
    }

    /** 出站：返回只含 traceparent 的头，调用方无条件 merge 进 MQ 消息属性即可。 */
    public static Map<String, String> injectHeaders() {
        String traceId = TraceIdContext.currentOrCreate();
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(TRACEPARENT, "00-" + traceId + "-" + spanId + "-01");
        return headers;
    }

    /** 入站：从消息头提取合法 traceId；缺失 / 畸形 / 全零返回空串。 */
    public static String extractTraceId(Map<String, String> headers) {
        if (headers == null) {
            return "";
        }
        String raw = headers.get(TRACEPARENT);
        if (raw == null) {
            return "";
        }
        Matcher matcher = TRACEPARENT_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            return "";
        }
        String traceId = matcher.group(1);
        if (!TraceIdContext.isValid(traceId) || ZERO_SPAN_ID.equals(matcher.group(2))) {
            return "";
        }
        return traceId;
    }
}
