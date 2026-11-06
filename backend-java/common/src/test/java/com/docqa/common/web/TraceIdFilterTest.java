package com.docqa.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.common.trace.TraceIdContext;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACE_PARENT = "00-" + TRACE_ID + "-b7ad6b7169203331-01";

    private TraceIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TraceIdFilter();
        TraceIdContext.clear();
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void continuesValidTraceParent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        request.addHeader(TraceIdFilter.TRACE_PARENT_HEADER, TRACE_PARENT);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observedTraceId = new AtomicReference<>();

        FilterChain chain = (servletRequest, servletResponse) -> observedTraceId.set(TraceIdContext.current());
        filter.doFilter(request, response, chain);

        assertThat(observedTraceId.get()).isEqualTo(TRACE_ID);
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo(TRACE_ID);
        assertThat(MDC.get(TraceIdContext.MDC_KEY)).isNull();
        assertThat(request.getAttribute(TraceIdContext.MDC_KEY)).isEqualTo(TRACE_ID);
    }

    @Test
    void usesValidFallbackHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo(TRACE_ID);
    }

    @Test
    void generatesTraceIdForInvalidOrMissingHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        request.addHeader(TraceIdFilter.TRACE_PARENT_HEADER, "00-00000000000000000000000000000000-b7ad6b7169203331-01");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
        });

        String generatedTraceId = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertThat(generatedTraceId).isNotEqualTo(TRACE_ID);
        assertThat(TraceIdContext.isValid(generatedTraceId)).isTrue();
    }

    @Test
    void clearsTraceIdWhenFilterChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(TraceIdContext.MDC_KEY)).isNull();
    }
}
