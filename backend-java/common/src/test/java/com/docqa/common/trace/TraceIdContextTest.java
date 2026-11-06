package com.docqa.common.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TraceIdContextTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @AfterEach
    void clearTraceId() {
        TraceIdContext.clear();
    }

    @Test
    void generatesValidTraceId() {
        String traceId = TraceIdContext.generate();

        assertThat(TraceIdContext.isValid(traceId)).isTrue();
    }

    @Test
    void readsAndRestoresTraceId() {
        TraceIdContext.set(TRACE_ID);

        assertThat(TraceIdContext.current()).isEqualTo(TRACE_ID);
        assertThat(TraceIdContext.currentOrCreate()).isEqualTo(TRACE_ID);

        TraceIdContext.clear();
        assertThat(TraceIdContext.current()).isNull();
    }

    @Test
    void rejectsInvalidTraceIds() {
        assertThat(TraceIdContext.isValid(null)).isFalse();
        assertThat(TraceIdContext.isValid("")).isFalse();
        assertThat(TraceIdContext.isValid("4BF92F3577B34DA6A3CE929D0E0E4736")).isFalse();
        assertThat(TraceIdContext.isValid("00000000000000000000000000000000")).isFalse();

        assertThatThrownBy(() -> TraceIdContext.set("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
