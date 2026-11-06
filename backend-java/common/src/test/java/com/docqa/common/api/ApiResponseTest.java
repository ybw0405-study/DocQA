package com.docqa.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.docqa.common.trace.TraceIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @AfterEach
    void clearTraceId() {
        TraceIdContext.clear();
    }

    @Test
    void buildsSuccessEnvelopeWithCurrentTraceId() {
        TraceIdContext.set(TRACE_ID);

        ApiResponse<String> response = ApiResponse.ok("data");

        assertThat(response.getCode()).isEqualTo("OK");
        assertThat(response.getMsg()).isEqualTo("success");
        assertThat(response.getData()).isEqualTo("data");
        assertThat(response.getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void createsTraceIdWhenMissing() {
        ApiResponse<Void> response = ApiResponse.ok(null);

        assertThat(TraceIdContext.isValid(response.getTraceId())).isTrue();
        assertThat(response.getData()).isNull();
    }
}
