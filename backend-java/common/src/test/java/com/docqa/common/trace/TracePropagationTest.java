package com.docqa.common.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 方案 4.3：出站注入 / 入站提取的 W3C trace 传播骨架。 */
class TracePropagationTest {

    @Test
    void injectProducesAParseableTraceparentForTheCurrentTraceId() {
        TraceIdContext.clear();
        try {
            Map<String, String> headers = TracePropagation.injectHeaders();
            assertThat(headers).containsKey(TracePropagation.TRACEPARENT);
            assertThat(TraceIdContext.isValid(TracePropagation.extractTraceId(headers))).isTrue();
        } finally {
            TraceIdContext.clear();
        }
    }

    @Test
    void injectedHeaderKeepsAnExplicitlySetTraceId() {
        String expected = "0123456789abcdef0123456789abcdef";
        TraceIdContext.set(expected);
        try {
            assertThat(TracePropagation.extractTraceId(TracePropagation.injectHeaders()))
                    .isEqualTo(expected);
        } finally {
            TraceIdContext.clear();
        }
    }

    @Test
    void malformedOrMissingHeadersYieldNoTraceId() {
        assertThat(TracePropagation.extractTraceId(Map.of())).isEmpty();
        assertThat(TracePropagation.extractTraceId(Map.of("traceparent", "garbage"))).isEmpty();
        assertThat(TracePropagation.extractTraceId(
                        Map.of("traceparent", "00-00000000000000000000000000000000-1111111111111111-01")))
                .isEmpty();
    }
}
